#!/usr/bin/env python3
"""Export private Speech-style cards into an import-compatible JSONL contract.

The curation contract, not a preview/month-plan, is the source of truth. This exporter deliberately omits original
paths, message IDs, ordinals, and provenance traces: the runtime needs only minimally generalized bubbles,
response-style metadata, and a non-reversible source fingerprint. It strips parser event/media metadata and
normalizes machine speaker labels. A formally approved card that becomes unusable after sanitization fails the export
instead of being silently included or discarded. The reviewed-preview profile is local RAG evaluation material until a
separate semantic-quality gate approves operational import. It never prints dialogue text.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any


IMPORT_SCHEMA = "nia-human-speech-style-import-card.v1"
SOURCE_SCHEMA = "nia-human-speech-card.v2"
PREVIEW_SOURCE_SCHEMA = "nia-human-speech-card-preview.v1"
REVIEW_SCHEMA = "nia-human-speech-card-review.v1"
FORMAL_QUALITY = "CURATION_APPROVED"
USER_RELEASED_REVIEW_QUALITY = "USER_RELEASED_REVIEW"
FORMAL_CONSENT_REVISION = "2026-08-04-curation-approved"
USER_RELEASED_REVIEW_CONSENT_REVISION = "2026-08-04-user-released-human-review"
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
DEFAULT_SOURCE_ROOTS = (Path("data/private/nia-human-dialogue/2026-08-01-manual-v2/curation/approved"),)
DEFAULT_PREVIEW_SOURCE_ROOTS = (
    Path("data/private/nia-human-dialogue/2026-08-01-manual-v2/curation/month-plans"),
    Path("data/private/nia-human-dialogue/2026-08-01-manual-v2/curation/source-month-plans"),
)
DEFAULT_INPUT_MANIFEST = Path("data/private/nia-human-dialogue/2026-08-01-manual-v2/manifest.json")
DEFAULT_CURATION_STATE = Path("data/private/nia-human-dialogue/2026-08-01-manual-v2/CURATION_STATE.json")
DEFAULT_REVIEW_ROOT = Path("data/private/nia-human-dialogue/2026-08-01-manual-v2/curation/reviews")
DEFAULT_SOURCE_COVERAGE_POLICY = Path(__file__).resolve().parents[1] / "central-server/src/main/resources/human-speech-style-source-coverage.json"
CARD_FILENAME = re.compile(r"human-speech-card-[0-9]{6}\.json")
PREVIEW_CARD_FILENAME = re.compile(r"card-[0-9]+\.json")
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
RUNTIME_INLINE_EVENT_METADATA = re.compile(r"\s*\[반응 시점\]")
RUNTIME_LEGACY_MARKER = re.compile(
    r"참여자\s*[AB]\b|\[인물\s*\d*\]|\[외부 링크\]|\[내용 없음 또는 만료된 첨부물\]|\[반응 시점\]|Reacted .+ to your message",
)


@dataclass(frozen=True)
class ExportProfile:
    name: str
    quality: str
    consent_revision: str
    source_schema: str
    default_source_roots: tuple[Path, ...]
    default_output_dir: Path
    requires_fresh_review: bool
    requires_explicit_user_release: bool


FORMAL_APPROVED_PROFILE = ExportProfile(
    name="formal-approved",
    quality=FORMAL_QUALITY,
    consent_revision=FORMAL_CONSENT_REVISION,
    source_schema=SOURCE_SCHEMA,
    default_source_roots=DEFAULT_SOURCE_ROOTS,
    default_output_dir=Path("data/private/nia-human-dialogue/2026-08-01-manual-v2/style-rag-runtime-import"),
    requires_fresh_review=True,
    requires_explicit_user_release=False,
)
USER_RELEASED_PREVIEW_PROFILE = ExportProfile(
    name="user-released-reviewed-previews",
    quality=USER_RELEASED_REVIEW_QUALITY,
    consent_revision=USER_RELEASED_REVIEW_CONSENT_REVISION,
    source_schema=PREVIEW_SOURCE_SCHEMA,
    default_source_roots=DEFAULT_PREVIEW_SOURCE_ROOTS,
    default_output_dir=Path("data/private/nia-human-dialogue/2026-08-01-manual-v2/style-rag-user-released-runtime-import"),
    requires_fresh_review=False,
    requires_explicit_user_release=True,
)
EXPORT_PROFILES = {
    FORMAL_APPROVED_PROFILE.name: FORMAL_APPROVED_PROFILE,
    USER_RELEASED_PREVIEW_PROFILE.name: USER_RELEASED_PREVIEW_PROFILE,
}


def main() -> int:
    args = parse_args()
    profile = EXPORT_PROFILES[args.input_profile]
    if profile.requires_explicit_user_release and not args.user_released_reviewed_previews:
        raise ValueError("user-released preview export requires --user-released-reviewed-previews")
    source_roots = [root.resolve() for root in args.source_roots or profile.default_source_roots]
    input_manifest = args.input_manifest.resolve()
    curation_state = args.curation_state.resolve()
    review_root = args.review_root.resolve()
    output_dir = (args.output_dir or profile.default_output_dir).resolve()
    expected_sources = read_expected_sources(input_manifest)
    source_coverage_policy = read_source_coverage_policy(DEFAULT_SOURCE_COVERAGE_POLICY)
    validate_expected_source_coverage(expected_sources, source_coverage_policy)
    if profile.requires_fresh_review:
        validate_curation_state(curation_state)
        reviews_by_card = read_reviews(review_root)
    else:
        reviews_by_card = {}
    cards, excluded_card_counts = read_cards(source_roots, expected_sources, reviews_by_card, profile)
    validate_complete_source_coverage(cards, expected_sources, profile)
    validate_source_traces_against_normalized_corpus(cards, input_manifest, profile)
    records: list[dict[str, Any]] = []
    sanitized_excluded_card_counts: Counter[str] = Counter()
    for card in cards:
        record = to_runtime_record(
            card,
            len(records) + 1,
            source_sha256_for_card(card, expected_sources),
            profile,
        )
        if record is None:
            sanitized_excluded_card_counts["no_usable_dialogue_after_runtime_sanitization"] += 1
            continue
        records.append(record)
    validate_runtime_records(records)
    validate_complete_runtime_source_coverage(records, expected_sources, profile)

    output_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(output_dir, 0o700)
    jsonl_path = output_dir / "human-speech-style-cards.jsonl"
    manifest_path = output_dir / "manifest.json"
    write_jsonl(jsonl_path, records)
    digest = sha256_file(jsonl_path)
    write_json(
        manifest_path,
        {
            "schema": "nia-human-speech-style-runtime-export-manifest.v2",
            "record_count": len(records),
            "jsonl_sha256": digest,
            "quality": profile.quality,
            "consent_revision": profile.consent_revision,
            "input_profile": profile.name,
            "response_mode_counts": dict(sorted(Counter(record["response_mode"] for record in records).items())),
            "accepted_card_count": len(records),
            "validated_input_card_count": len(cards),
            "scanned_card_count": len(cards) + sum(excluded_card_counts.values()),
            "runtime_excluded_card_counts": dict(sorted((excluded_card_counts + sanitized_excluded_card_counts).items())),
            "source_count": len({record["source_fingerprint"] for record in records}),
            "source_fingerprint_count": len({record["source_fingerprint"] for record in records}),
            "source_fingerprint_set_sha256": source_fingerprint_set_sha256(
                {record["source_fingerprint"] for record in records},
            ),
            "expected_source_count": source_coverage_policy["source_count"],
            "expected_source_fingerprint_set_sha256": source_coverage_policy["source_fingerprint_set_sha256"],
            "source_coverage_complete": True,
            "input_manifest_sha256": sha256_file(input_manifest),
            "all_cards_formally_approved": profile.requires_fresh_review,
            "all_cards_user_released": profile.requires_explicit_user_release,
        },
    )
    print(
        "human-speech-style export complete "
        f"records={len(records)} profile={profile.name} quality={profile.quality} "
        f"sha256={digest} modes={dict(sorted(Counter(record['response_mode'] for record in records).items()))} "
        f"excluded={dict(sorted((excluded_card_counts + sanitized_excluded_card_counts).items()))}",
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
        "--input-profile",
        choices=tuple(EXPORT_PROFILES),
        default=FORMAL_APPROVED_PROFILE.name,
        help="formal approved cards by default; reviewed previews need explicit user release for local RAG evaluation",
    )
    parser.add_argument(
        "--user-released-reviewed-previews",
        action="store_true",
        help="explicit acknowledgement required before exporting reviewed previews for local evaluation, not DB import",
    )
    parser.add_argument(
        "--input-manifest",
        type=Path,
        default=DEFAULT_INPUT_MANIFEST,
        help="private source manifest used to validate approved-card source fingerprints",
    )
    parser.add_argument(
        "--curation-state",
        type=Path,
        default=DEFAULT_CURATION_STATE,
        help="private curation state used to enforce formal export gates",
    )
    parser.add_argument(
        "--review-root",
        type=Path,
        default=DEFAULT_REVIEW_ROOT,
        help="private fresh-verifier review directory",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        help="private output directory; profile-specific default when omitted",
    )
    return parser.parse_args()


def read_cards(
    source_roots: list[Path],
    expected_sources: dict[str, str],
    reviews_by_card: dict[str, list[dict[str, Any]]],
    profile: ExportProfile = FORMAL_APPROVED_PROFILE,
) -> tuple[list[dict[str, Any]], Counter[str]]:
    paths: list[Path] = []
    for source_root in source_roots:
        if not source_root.is_dir():
            raise ValueError("private human-review card directory does not exist")
        filename = CARD_FILENAME if profile.requires_fresh_review else PREVIEW_CARD_FILENAME
        paths.extend(path for path in source_root.rglob("*.json") if filename.fullmatch(path.name))
    if not paths:
        raise ValueError("no private human-review cards found")
    cards: list[dict[str, Any]] = []
    excluded_card_counts: Counter[str] = Counter()
    card_ids: set[str] = set()
    for path in sorted(set(paths)):
        try:
            with path.open(encoding="utf-8") as handle:
                card = json.load(handle)
        except (OSError, json.JSONDecodeError) as error:
            raise ValueError("formally approved private card could not be read") from error
        exclusion_reason = runtime_exclusion_reason(card)
        if profile.requires_fresh_review and exclusion_reason is not None:
            excluded_card_counts[exclusion_reason] += 1
            continue
        if profile.requires_fresh_review:
            validate_source_card(card, expected_sources, reviews_by_card)
        else:
            validate_user_released_preview_card(card, expected_sources)
        card_id = card["card_id"]
        if card_id in card_ids:
            raise ValueError("formally approved private card id is duplicated")
        card_ids.add(card_id)
        cards.append(card)
    return sorted(cards, key=lambda card: card["card_id"]), excluded_card_counts


def validate_complete_source_coverage(
    cards: list[dict[str, Any]],
    expected_sources: dict[str, str],
    profile: ExportProfile,
) -> None:
    """Fail every export profile if any expected source is omitted."""
    covered_sources = {card["source_id"] for card in cards}
    if covered_sources != set(expected_sources):
        raise ValueError("private Speech-style export does not cover every expected source")


def validate_complete_runtime_source_coverage(
    records: list[dict[str, Any]],
    expected_sources: dict[str, str],
    profile: ExportProfile,
) -> None:
    """Ensure sanitization did not silently remove an expected source."""
    expected_fingerprints = {f"sha256:{source_sha256}" for source_sha256 in expected_sources.values()}
    covered_fingerprints = {record["source_fingerprint"] for record in records}
    if covered_fingerprints != expected_fingerprints:
        raise ValueError("private Speech-style runtime export does not preserve every expected source")


def runtime_exclusion_reason(card: dict[str, Any]) -> str | None:
    if card.get("schema") == "nia-human-speech-card.v1":
        return "legacy_schema"
    privacy = card.get("privacy")
    if isinstance(privacy, dict) and privacy.get("runtime_import") == "BLOCKED":
        return "legacy_pre_runtime_contract"
    return None


def validate_source_card(
    card: dict[str, Any],
    expected_sources: dict[str, str],
    reviews_by_card: dict[str, list[dict[str, Any]]],
) -> None:
    if card.get("schema") != SOURCE_SCHEMA:
        raise ValueError("formally approved private card schema is unsupported")
    if card.get("status") != "APPROVED":
        raise ValueError("private card is not formally approved")
    if card.get("decision") not in ALLOWED_DECISIONS:
        raise ValueError("formally approved private card decision is unsupported")
    if card.get("response_mode") not in ALLOWED_MODES:
        raise ValueError("formally approved private card response mode is unsupported")
    if not isinstance(card.get("context_messages"), list) or not isinstance(card.get("actual_human_reply"), list):
        raise ValueError("formally approved private card bubbles are missing")
    if not card.get("situation") or not isinstance(card.get("style_signals"), list):
        raise ValueError("formally approved private card metadata is missing")
    if not isinstance(card.get("combined_chars"), int) or not 1 <= card["combined_chars"] <= 350:
        raise ValueError("formally approved private card size is invalid")
    if not isinstance(card.get("card_id"), str) or not card["card_id"].strip():
        raise ValueError("formally approved private card id is missing")
    if not isinstance(card.get("source_id"), str) or not card["source_id"].strip():
        raise ValueError("formally approved private card source id is missing")
    source_sha256_for_card(card, expected_sources)
    validate_source_trace(card)
    validate_latest_fresh_review(card["card_id"], reviews_by_card.get(card["card_id"], []))


def validate_user_released_preview_card(card: dict[str, Any], expected_sources: dict[str, str]) -> None:
    if card.get("schema") != PREVIEW_SOURCE_SCHEMA:
        raise ValueError("reviewed preview card schema is unsupported")
    if card.get("status") != "PLANNED_REQUIRES_FRESH_VERIFIER":
        raise ValueError("reviewed preview card status is unsupported")
    if card.get("candidate_decision") not in ALLOWED_DECISIONS:
        raise ValueError("reviewed preview card decision is unsupported")
    if card.get("response_mode") not in ALLOWED_MODES:
        raise ValueError("reviewed preview card response mode is unsupported")
    if not isinstance(card.get("context_messages"), list) or not isinstance(card.get("actual_human_reply"), list):
        raise ValueError("reviewed preview card bubbles are missing")
    if not card.get("context_messages") or not card.get("actual_human_reply"):
        raise ValueError("reviewed preview card bubbles are empty")
    if not card.get("situation") or not isinstance(card.get("style_signals"), list):
        raise ValueError("reviewed preview card metadata is missing")
    if not isinstance(card.get("combined_chars"), int) or not 1 <= card["combined_chars"] <= 350:
        raise ValueError("reviewed preview card size is invalid")
    if not isinstance(card.get("card_id"), str) or not card["card_id"].strip():
        raise ValueError("reviewed preview card id is missing")
    source_sha256 = source_sha256_for_card(card, expected_sources)
    privacy = card.get("privacy")
    if not isinstance(privacy, dict):
        raise ValueError("reviewed preview card privacy metadata is missing")
    if privacy.get("display_only_not_rag_input") is not True:
        raise ValueError("reviewed preview card is not a display-only source artifact")
    if privacy.get("requires_fresh_verifier") is not True or privacy.get("runtime_import_blocked") is not True:
        raise ValueError("reviewed preview card does not retain its pre-release safety boundary")
    validate_preview_source_trace(card, source_sha256)


def validate_preview_source_trace(card: dict[str, Any], source_sha256: str) -> None:
    source_trace = card.get("source_trace")
    if not isinstance(source_trace, dict) or source_trace.get("source_sha256") != source_sha256:
        raise ValueError("reviewed preview card source trace does not match its source manifest")
    records = source_trace.get("records")
    messages = [*card["context_messages"], *card["actual_human_reply"]]
    if not isinstance(records, list) or not records or len(records) != len(messages):
        raise ValueError("reviewed preview card source trace is missing or incomplete")
    actual_trace = [preview_source_trace_entry(entry) for entry in records]
    context_start = card.get("context_start_ordinal")
    context_end = card.get("context_end_ordinal")
    reply_start = card.get("reply_start_ordinal")
    reply_end = card.get("reply_end_ordinal")
    ordinal_bounds = (context_start, context_end, reply_start, reply_end)
    if not all(isinstance(ordinal, int) and ordinal > 0 for ordinal in ordinal_bounds):
        raise ValueError("reviewed preview card source range is invalid")
    if context_start > context_end or reply_start <= context_end or reply_end < reply_start:
        raise ValueError("reviewed preview card source range is invalid")
    context_count = len(card["context_messages"])
    context_trace = actual_trace[:context_count]
    reply_trace = actual_trace[context_count:]
    ordinals = [entry["ordinal"] for entry in actual_trace]
    if (
        ordinals != sorted(set(ordinals))
        or context_trace[0]["ordinal"] != context_start
        or context_trace[-1]["ordinal"] != context_end
        or reply_trace[0]["ordinal"] != reply_start
        or reply_trace[-1]["ordinal"] != reply_end
    ):
        raise ValueError("reviewed preview card source trace does not match its range")


def preview_source_trace_entry(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError("reviewed preview card source trace entry is invalid")
    ordinal = value.get("ordinal")
    speaker = value.get("source_speaker")
    content_sha256 = value.get("content_sha256")
    if not isinstance(ordinal, int) or ordinal < 1:
        raise ValueError("reviewed preview card source trace ordinal is invalid")
    if not isinstance(speaker, str) or not speaker.strip():
        raise ValueError("reviewed preview card source trace speaker is invalid")
    if not isinstance(content_sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", content_sha256):
        raise ValueError("reviewed preview card source trace hash is invalid")
    return {"ordinal": ordinal, "speaker": speaker, "content_sha256": content_sha256}


def source_sha256_for_card(card: dict[str, Any], expected_sources: dict[str, str]) -> str:
    source_id = card.get("source_id")
    if not isinstance(source_id, str) or not source_id.strip():
        raise ValueError("formally approved private card source id is missing")
    source_sha256 = expected_sources.get(source_id)
    if source_sha256 is None:
        raise ValueError("formally approved private card source does not match the source manifest")
    return source_sha256


def validate_source_trace(card: dict[str, Any]) -> None:
    source_trace = card.get("source_trace")
    messages = [*card["context_messages"], *card["actual_human_reply"]]
    if not isinstance(source_trace, list) or not source_trace or len(source_trace) != len(messages):
        raise ValueError("formally approved private card source trace is missing or incomplete")
    actual_trace = [source_trace_entry(entry) for entry in source_trace]
    context_start = card.get("context_start_ordinal")
    context_end = card.get("context_end_ordinal")
    reply_start = card.get("reply_start_ordinal")
    reply_end = card.get("reply_end_ordinal")
    ordinal_bounds = (context_start, context_end, reply_start, reply_end)
    if not all(isinstance(ordinal, int) and ordinal > 0 for ordinal in ordinal_bounds):
        raise ValueError("formally approved private card source range is invalid")
    if context_start > context_end or reply_start != context_end + 1 or reply_end < reply_start:
        raise ValueError("formally approved private card source range is not contiguous")
    ordinals = [entry["ordinal"] for entry in actual_trace]
    if ordinals != list(range(context_start, reply_end + 1)):
        raise ValueError("formally approved private card source trace does not match its range")


def source_trace_entry(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError("formally approved private card source trace entry is invalid")
    ordinal = value.get("ordinal")
    speaker = value.get("speaker")
    content_sha256 = value.get("content_sha256")
    if not isinstance(ordinal, int) or ordinal < 1:
        raise ValueError("formally approved private card source trace ordinal is invalid")
    if not isinstance(speaker, str) or not speaker.strip():
        raise ValueError("formally approved private card source trace speaker is invalid")
    if not isinstance(content_sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", content_sha256):
        raise ValueError("formally approved private card source trace hash is invalid")
    return {"ordinal": ordinal, "speaker": speaker, "content_sha256": content_sha256}


def source_trace_entries_for_card(card: dict[str, Any], profile: ExportProfile) -> list[dict[str, Any]]:
    if profile.requires_fresh_review:
        source_trace = card.get("source_trace")
        if not isinstance(source_trace, list):
            raise ValueError("formally approved private card source trace is missing")
        return [source_trace_entry(entry) for entry in source_trace]
    source_trace = card.get("source_trace")
    if not isinstance(source_trace, dict) or not isinstance(source_trace.get("records"), list):
        raise ValueError("reviewed preview card source trace is missing")
    return [preview_source_trace_entry(entry) for entry in source_trace["records"]]


def validate_source_traces_against_normalized_corpus(
    cards: list[dict[str, Any]],
    input_manifest: Path,
    profile: ExportProfile,
) -> None:
    required_by_source: dict[str, dict[int, tuple[str, str]]] = defaultdict(dict)
    skipped_by_source: dict[str, set[int]] = defaultdict(set)
    for card in cards:
        source_id = card["source_id"]
        entries = source_trace_entries_for_card(card, profile)
        for entry in entries:
            expected = (entry["speaker"], entry["content_sha256"])
            prior = required_by_source[source_id].setdefault(entry["ordinal"], expected)
            if prior != expected:
                raise ValueError("private human-review cards disagree about a source trace entry")
        if not profile.requires_fresh_review:
            present_ordinals = {entry["ordinal"] for entry in entries}
            skipped_by_source[source_id].update(
                set(range(card["context_start_ordinal"], card["reply_end_ordinal"] + 1)) - present_ordinals,
            )

    corpus_root = input_manifest.parent
    for source_id, required in sorted(required_by_source.items()):
        source_manifest_path = corpus_root / source_id / "manifest.json"
        try:
            with source_manifest_path.open(encoding="utf-8") as handle:
                source_manifest = json.load(handle)
        except (OSError, json.JSONDecodeError) as error:
            raise ValueError("private normalized source manifest could not be read") from error
        source_file = source_manifest.get("source_file")
        expected_count = source_manifest.get("message_count")
        if not isinstance(source_file, str) or not isinstance(expected_count, int) or expected_count < 1:
            raise ValueError("private normalized source manifest is invalid")
        normalized_path = Path(source_file)
        if not normalized_path.is_absolute():
            normalized_path = Path.cwd() / normalized_path
        normalized_path = normalized_path.resolve()
        private_root = corpus_root.parent.resolve()
        if not normalized_path.is_relative_to(private_root):
            raise ValueError("private normalized source path is outside the private corpus")
        observed_count = 0
        found: dict[int, tuple[str, str]] = {}
        skipped = skipped_by_source[source_id]
        found_skipped: set[int] = set()
        try:
            with normalized_path.open(encoding="utf-8") as handle:
                for line in handle:
                    if not line.strip():
                        continue
                    observed_count += 1
                    row = json.loads(line)
                    ordinal = row.get("ordinal")
                    if ordinal in required:
                        row_source_id = row.get("source_id")
                        speaker = row.get("speaker")
                        content_sha256 = row.get("content_sha256")
                        if row_source_id != source_id or not isinstance(speaker, str) or not isinstance(content_sha256, str):
                            raise ValueError("private normalized source trace row is invalid")
                        found[ordinal] = (speaker, content_sha256)
                    if ordinal in skipped:
                        flags = row.get("flags")
                        if not is_skippable_runtime_event(flags):
                            raise ValueError("reviewed preview card omits a non-event source message")
                        found_skipped.add(ordinal)
        except (OSError, json.JSONDecodeError) as error:
            raise ValueError("private normalized source could not be read") from error
        if observed_count != expected_count or found != required or found_skipped != skipped:
            raise ValueError("private human-review source trace does not match normalized source data")


def is_skippable_runtime_event(flags: Any) -> bool:
    return isinstance(flags, list) and any(flag in {"media_marker", "reaction_marker", "empty_content"} for flag in flags)


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


def read_source_coverage_policy(path: Path) -> dict[str, Any]:
    try:
        with path.open(encoding="utf-8") as handle:
            policy = json.load(handle)
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("human speech style source coverage policy could not be read") from error
    if set(policy) != {"schema", "source_count", "source_fingerprint_set_sha256"}:
        raise ValueError("human speech style source coverage policy fields are invalid")
    if policy.get("schema") != "nia-human-speech-style-source-coverage.v1":
        raise ValueError("human speech style source coverage policy schema is invalid")
    if not isinstance(policy.get("source_count"), int) or policy["source_count"] < 1:
        raise ValueError("human speech style source coverage policy source count is invalid")
    if not isinstance(policy.get("source_fingerprint_set_sha256"), str) or not re.fullmatch(
        r"[0-9a-f]{64}",
        policy["source_fingerprint_set_sha256"],
    ):
        raise ValueError("human speech style source coverage policy fingerprint digest is invalid")
    return policy


def source_fingerprint_set_sha256(fingerprints: set[str]) -> str:
    if not fingerprints or not all(re.fullmatch(r"sha256:[0-9a-f]{64}", fingerprint) for fingerprint in fingerprints):
        raise ValueError("human speech style source fingerprint set is invalid")
    return hashlib.sha256("\n".join(sorted(fingerprints)).encode()).hexdigest()


def validate_expected_source_coverage(expected_sources: dict[str, str], policy: dict[str, Any]) -> None:
    expected_fingerprints = {f"sha256:{source_sha256}" for source_sha256 in expected_sources.values()}
    if len(expected_fingerprints) != policy["source_count"]:
        raise ValueError("private source manifest does not match required source coverage count")
    if source_fingerprint_set_sha256(expected_fingerprints) != policy["source_fingerprint_set_sha256"]:
        raise ValueError("private source manifest does not match required source coverage set")


def validate_curation_state(path: Path) -> None:
    try:
        with path.open(encoding="utf-8") as handle:
            state = json.load(handle)
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("private curation state could not be read") from error
    if state.get("style_rag_contract", {}).get("pre_contract_approved_cards_reaudit_required") is not False:
        raise ValueError("private curation re-audit is incomplete")
    if state.get("style_rag_reaudit", {}).get("active_card_id") is not None:
        raise ValueError("private curation re-audit has an active card")


def read_reviews(root: Path) -> dict[str, list[dict[str, Any]]]:
    if not root.is_dir():
        raise ValueError("private fresh-verifier review directory does not exist")
    reviews_by_card: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for path in sorted(root.rglob("*.json")):
        try:
            with path.open(encoding="utf-8") as handle:
                review = json.load(handle)
        except (OSError, json.JSONDecodeError) as error:
            raise ValueError("private fresh-verifier review could not be read") from error
        if review.get("schema") != REVIEW_SCHEMA:
            raise ValueError("private fresh-verifier review schema is unsupported")
        card_id = review.get("card_id")
        if isinstance(card_id, str) and card_id:
            reviews_by_card[card_id].append(review)
    return reviews_by_card


def validate_latest_fresh_review(card_id: str, reviews: list[dict[str, Any]]) -> None:
    if not reviews:
        raise ValueError("formally approved private card has no fresh-verifier review")
    latest = max(reviews, key=lambda review: (str(review.get("reviewed_at", "")), str(review.get("review_id", ""))))
    criteria = latest.get("criteria")
    if latest.get("fresh_context") is not True:
        raise ValueError("formally approved private card latest review is not fresh-context")
    if latest.get("verdict") != "PASS":
        raise ValueError("formally approved private card latest review did not pass")
    if not isinstance(criteria, dict) or any(criteria.get(key) != "PASS" for key in "ABCDEF"):
        raise ValueError("formally approved private card latest review does not pass every criterion")


def to_runtime_record(
    card: dict[str, Any],
    ordinal: int,
    source_sha256: str,
    profile: ExportProfile = FORMAL_APPROVED_PROFILE,
) -> dict[str, Any] | None:
    if not re.fullmatch(r"[0-9a-f]{64}", source_sha256):
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
        "quality": profile.quality,
        "source_fingerprint": f"sha256:{source_sha256}",
        "consent_revision": profile.consent_revision,
        "combined_chars": card["combined_chars"],
        # 카드의 자연 가명 목록 자체는 runtime artifact에 싣지 않는다. 다만 외부 Speech 참고 표면에
        # 그 가명이 남았는지 여부만 비가역 boolean으로 전달해 materializer가 fail-closed 할 수 있게 한다.
        "response_surface_has_card_local_alias": response_surface_has_card_local_alias(card, response_bubbles),
        "embedding_model": "text-embedding-3-small",
    }


def to_bubble(message: dict[str, Any]) -> dict[str, str] | None:
    if not isinstance(message, dict):
        raise ValueError("private human-review bubble is invalid")
    text = sanitize_runtime_bubble_text(bubble_content(message))
    if not text:
        return None
    return {
        "speaker": normalize_runtime_speaker(message.get("speaker")),
        "text": text,
    }


def bubble_content(message: dict[str, Any]) -> Any:
    content = message.get("content")
    text = message.get("text")
    if content is not None and text is not None and content != text:
        raise ValueError("private human-review bubble has conflicting content")
    return content if content is not None else text


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
        line = RUNTIME_INLINE_EVENT_METADATA.sub("", line).strip()
        if not line or RUNTIME_EVENT_ONLY_LINE.fullmatch(line):
            continue
        retained_lines.append(line)
    return "\n".join(retained_lines)


def response_surface_has_card_local_alias(card: dict[str, Any], response_bubbles: list[dict[str, str]]) -> bool:
    """카드 안에서만 쓰는 자연 가명이 provider 참고 응답 표면에 남았는지 확인한다.

    가명 목록은 source-local privacy metadata이므로 runtime JSONL에 복사하지 않는다. 이 함수는
    response bubble에 실제로 등장했는지라는 boolean만 남긴다. metadata가 없는 구형 카드도 안전하게
    false가 되며, 그 경우 materializer의 독립적인 표면 안전 규칙이 계속 적용된다.
    """
    aliases = card_local_aliases(card)
    if not aliases:
        return False
    return any(alias in bubble["text"] for alias in aliases for bubble in response_bubbles)


def card_local_aliases(card: dict[str, Any]) -> set[str]:
    """서로 다른 preview card schema의 card-local alias metadata를 읽되 값은 반환 밖으로 노출하지 않는다."""
    aliases: set[str] = set()
    for section_name, key in (
        ("generalization", "card_local_marker_aliases_used"),
        ("privacy", "card_local_aliases_used"),
    ):
        section = card.get(section_name)
        if not isinstance(section, dict):
            continue
        values = section.get(key)
        if not isinstance(values, list):
            continue
        for value in values:
            if isinstance(value, str):
                alias = value.strip()
                if alias:
                    aliases.add(alias)
    return aliases


def validate_runtime_records(records: list[dict[str, Any]]) -> None:
    if not records:
        raise ValueError("no usable private human-review cards remain after runtime sanitization")
    for record in records:
        metadata = [record["situation"], *record["style_signals"]]
        if any(RUNTIME_LEGACY_MARKER.search(value) for value in metadata):
            raise ValueError("runtime human-review card metadata retains parser metadata")
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
