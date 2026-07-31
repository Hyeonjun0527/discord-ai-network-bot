#!/usr/bin/env python3
"""Prepare consented, anonymized chat exports for private human review.

The command never sends data over the network and never prints message text. It
copies the sources into a private snapshot, normalizes messages, and creates
response-scene candidates whose review status starts as PENDING.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import tempfile
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Dict, Iterable, Iterator, List, Optional, Sequence, Tuple


EXPLICIT_MESSAGE = re.compile(
    r"^\[(?P<date>\d{4}-\d{2}-\d{2})(?: (?P<time>\d{2}:\d{2}))?\]\s+참여자 (?P<speaker>[AB]):\s?(?P<content>.*)$"
)
BARE_MESSAGE = re.compile(r"^참여자 (?P<speaker>[AB]):\s?(?P<content>.*)$")
MONTH_HEADING = re.compile(r"^\[(?P<year>\d{4})-(?P<month>\d{2})\]\s*$")
PATH_IN_MANIFEST = re.compile(r"/[^\s]+\.txt")
MEDIA_MARKER = re.compile(r"사진|이미지|동영상|릴스|reel|gif|첨부|링크", re.IGNORECASE)
REACTION_MARKER = re.compile(r"반응|좋아요|reaction", re.IGNORECASE)

SCHEMA_VERSION = "nia-human-dialogue-scenes.v1"
MAX_CONTEXT_TURNS = 4
MAX_REVIEW_BATCH_SIZE = 250
SESSION_GAP_MINUTES = 30


@dataclass
class Message:
    source_id: str
    ordinal: int
    speaker: str
    content: str
    line_start: int
    line_end: int
    timestamp: Optional[str]
    timestamp_precision: str
    period_key: Optional[str]
    continuation_lines: int = 0
    flags: List[str] = field(default_factory=list)

    @property
    def message_id(self) -> str:
        return f"{self.source_id}-m{self.ordinal:06d}"

    def to_dict(self) -> Dict[str, object]:
        return {
            "message_id": self.message_id,
            "source_id": self.source_id,
            "ordinal": self.ordinal,
            "speaker": self.speaker,
            "timestamp": self.timestamp,
            "timestamp_precision": self.timestamp_precision,
            "period_key": self.period_key,
            "content": self.content,
            "content_sha256": sha256_text(self.content),
            "line_start": self.line_start,
            "line_end": self.line_end,
            "continuation_lines": self.continuation_lines,
            "flags": self.flags,
        }


@dataclass
class ParseResult:
    messages: List[Message]
    skipped_prologue_lines: int
    continuation_lines: int


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_text(value: str) -> str:
    return sha256_bytes(value.encode("utf-8"))


def read_manifest_paths(manifest: Path) -> List[Path]:
    text = manifest.read_text(encoding="utf-8")
    paths = [Path(match).expanduser().resolve() for match in PATH_IN_MANIFEST.findall(text)]
    if not paths:
        raise ValueError("manifest에 .txt 경로가 없습니다")
    if len(paths) != len(set(paths)):
        raise ValueError("manifest에 중복 경로가 있습니다")
    for path in paths:
        if not path.is_file() or path.suffix.lower() != ".txt":
            raise ValueError(f"읽을 수 있는 txt 파일이 아닙니다: {path}")
    return paths


def parse_messages(source_id: str, text: str) -> ParseResult:
    messages: List[Message] = []
    current: Optional[Message] = None
    current_period: Optional[str] = None
    skipped_prologue = 0
    continuation_count = 0

    def flush() -> None:
        nonlocal current
        if current is None:
            return
        current.content = current.content.strip()
        current.flags = content_flags(current.content, current.continuation_lines)
        messages.append(current)
        current = None

    for line_number, raw_line in enumerate(text.splitlines(), start=1):
        line = raw_line.rstrip("\r")
        explicit = EXPLICIT_MESSAGE.match(line)
        bare = BARE_MESSAGE.match(line)
        month = MONTH_HEADING.match(line.strip())

        if explicit:
            flush()
            date = explicit.group("date")
            time = explicit.group("time")
            timestamp = f"{date}T{time}:00" if time else date
            current_period = date
            current = Message(
                source_id=source_id,
                ordinal=len(messages) + 1,
                speaker=explicit.group("speaker"),
                content=explicit.group("content"),
                line_start=line_number,
                line_end=line_number,
                timestamp=timestamp,
                timestamp_precision="minute" if time else "day",
                period_key=current_period,
            )
            continue

        if bare:
            flush()
            current = Message(
                source_id=source_id,
                ordinal=len(messages) + 1,
                speaker=bare.group("speaker"),
                content=bare.group("content"),
                line_start=line_number,
                line_end=line_number,
                timestamp=current_period,
                timestamp_precision="month" if current_period else "unknown",
                period_key=current_period,
            )
            continue

        if month:
            flush()
            current_period = f"{month.group('year')}-{month.group('month')}"
            continue

        if not line.strip():
            continue

        if current is None:
            skipped_prologue += 1
            continue

        current.content = f"{current.content}\n{line}" if current.content else line
        current.line_end = line_number
        current.continuation_lines += 1
        continuation_count += 1

    flush()
    return ParseResult(messages, skipped_prologue, continuation_count)


def content_flags(content: str, continuation_lines: int) -> List[str]:
    flags: List[str] = []
    if MEDIA_MARKER.search(content):
        flags.append("media_marker")
    if REACTION_MARKER.search(content):
        flags.append("reaction_marker")
    if continuation_lines:
        flags.append("multiline_or_event_metadata")
    if not content:
        flags.append("empty_content")
    return flags


def message_starts_new_session(previous: Message, current: Message) -> bool:
    if previous.period_key and current.period_key and previous.period_key != current.period_key:
        return True
    if previous.timestamp_precision != "minute" or current.timestamp_precision != "minute":
        return False
    previous_time = datetime.fromisoformat(previous.timestamp or "")
    current_time = datetime.fromisoformat(current.timestamp or "")
    return (current_time - previous_time).total_seconds() > SESSION_GAP_MINUTES * 60


def sessions(messages: Sequence[Message]) -> Iterator[List[Message]]:
    current: List[Message] = []
    for message in messages:
        if current and message_starts_new_session(current[-1], message):
            yield current
            current = []
        current.append(message)
    if current:
        yield current


def speaker_turns(messages: Sequence[Message]) -> List[List[Message]]:
    turns: List[List[Message]] = []
    for message in messages:
        if turns and turns[-1][-1].speaker == message.speaker:
            turns[-1].append(message)
        else:
            turns.append([message])
    return turns


def build_scene_candidates(source_id: str, messages: Sequence[Message]) -> List[Dict[str, object]]:
    candidates: List[Dict[str, object]] = []
    session_number = 0
    for session_messages in sessions(messages):
        session_number += 1
        turns = speaker_turns(session_messages)
        for target_index in range(1, len(turns)):
            response = turns[target_index]
            context_turns = turns[max(0, target_index - MAX_CONTEXT_TURNS) : target_index]
            if not context_turns or context_turns[-1][-1].speaker == response[0].speaker:
                continue
            context = [message for turn in context_turns for message in turn]
            scene_key = "|".join(message.message_id for message in context + response)
            candidate_id = f"scene-{sha256_text(scene_key)[:20]}"
            canonical = canonical_scene(context, response)
            flags = scene_flags(context, response)
            candidates.append(
                {
                    "schema": SCHEMA_VERSION,
                    "candidate_id": candidate_id,
                    "source_id": source_id,
                    "session_number": session_number,
                    "direction": f"{context[-1].speaker}_TO_{response[0].speaker}",
                    "context": [compact_message(message) for message in context],
                    "response": [compact_message(message) for message in response],
                    "context_message_ids": [message.message_id for message in context],
                    "response_message_ids": [message.message_id for message in response],
                    "canonical_sha256": sha256_text(canonical),
                    "flags": flags,
                    "duplicate_of": None,
                    "review": empty_review(),
                }
            )
    return candidates


def compact_message(message: Message) -> Dict[str, object]:
    return {
        "speaker": message.speaker,
        "content": message.content,
        "timestamp": message.timestamp,
        "flags": message.flags,
    }


def canonical_scene(context: Sequence[Message], response: Sequence[Message]) -> str:
    context_lines = [f"{message.speaker}:{normalize_content(message.content)}" for message in context]
    response_lines = [f"{message.speaker}:{normalize_content(message.content)}" for message in response]
    return "\n".join(context_lines + ["---RESPONSE---"] + response_lines)


def normalize_content(content: str) -> str:
    return re.sub(r"\s+", " ", content).strip()


def scene_flags(context: Sequence[Message], response: Sequence[Message]) -> List[str]:
    flags: List[str] = []
    if any("media_marker" in message.flags for message in context):
        flags.append("media_in_context")
    if any("media_marker" in message.flags for message in response):
        flags.append("media_in_response")
    if any("reaction_marker" in message.flags for message in context + response):
        flags.append("reaction_metadata")
    response_chars = sum(len(message.content) for message in response)
    if response_chars <= 4:
        flags.append("very_short_response")
    if len(response) > 8 or response_chars > 2_000:
        flags.append("oversized_response")
    if any(message.timestamp_precision == "unknown" for message in context + response):
        flags.append("timestamp_unknown")
    return flags


def empty_review() -> Dict[str, object]:
    return {
        "status": "PENDING",
        "reviewer": None,
        "reviewed_at": None,
        "suitability": None,
        "situation_tags": [],
        "emotion_tags": [],
        "response_move_tags": [],
        "tone_tags": [],
        "relationship_tone": None,
        "spoiler_or_private_context": False,
        "notes": None,
    }


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    path.chmod(0o600)


def write_jsonl(path: Path, values: Iterable[Dict[str, object]]) -> int:
    count = 0
    with path.open("w", encoding="utf-8") as handle:
        for value in values:
            handle.write(json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n")
            count += 1
    path.chmod(0o600)
    return count


def chunks(values: Sequence[Dict[str, object]], size: int) -> Iterator[Sequence[Dict[str, object]]]:
    for start in range(0, len(values), size):
        yield values[start : start + size]


def prepare(manifest: Path, output: Path) -> Dict[str, object]:
    if output.exists():
        raise ValueError(f"기존 snapshot을 덮어쓰지 않습니다: {output}")
    source_paths = read_manifest_paths(manifest)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.parent.chmod(0o700)
    temp = Path(tempfile.mkdtemp(prefix=f".{output.name}.", dir=str(output.parent)))
    os.chmod(temp, 0o700)
    try:
        source_dir = temp / "sources"
        normalized_dir = temp / "normalized"
        candidates_dir = temp / "candidates"
        review_dir = temp / "review" / "pending-batches"
        reports_dir = temp / "reports"
        for directory in (source_dir, normalized_dir, candidates_dir, review_dir, reports_dir):
            directory.mkdir(parents=True, exist_ok=True)
            directory.chmod(0o700)

        manifest_rows: List[Dict[str, object]] = []
        all_candidates: List[Dict[str, object]] = []
        total_messages = 0
        total_continuations = 0
        total_skipped_prologue = 0

        for index, source_path in enumerate(source_paths, start=1):
            raw = source_path.read_bytes()
            digest = sha256_bytes(raw)
            source_id = f"source-{index:02d}-{digest[:12]}"
            destination = source_dir / f"{source_id}.txt"
            destination.write_bytes(raw)
            destination.chmod(0o400)

            parsed = parse_messages(source_id, raw.decode("utf-8-sig"))
            write_jsonl(normalized_dir / f"{source_id}.messages.jsonl", (m.to_dict() for m in parsed.messages))
            candidates = build_scene_candidates(source_id, parsed.messages)
            all_candidates.extend(candidates)
            total_messages += len(parsed.messages)
            total_continuations += parsed.continuation_lines
            total_skipped_prologue += parsed.skipped_prologue_lines
            manifest_rows.append(
                {
                    "source_id": source_id,
                    "original_filename": source_path.name,
                    "sha256": digest,
                    "bytes": len(raw),
                    "message_count": len(parsed.messages),
                    "scene_candidate_count": len(candidates),
                    "continuation_line_count": parsed.continuation_lines,
                    "skipped_prologue_line_count": parsed.skipped_prologue_lines,
                }
            )

        first_by_hash: Dict[str, str] = {}
        unique_pending: List[Dict[str, object]] = []
        duplicate_count = 0
        for candidate in all_candidates:
            digest = str(candidate["canonical_sha256"])
            original = first_by_hash.get(digest)
            if original is None:
                first_by_hash[digest] = str(candidate["candidate_id"])
                unique_pending.append(candidate)
            else:
                candidate["duplicate_of"] = original
                candidate["flags"] = list(candidate["flags"]) + ["exact_duplicate"]
                candidate["review"] = {**empty_review(), "status": "DUPLICATE"}
                duplicate_count += 1

        write_jsonl(candidates_dir / "all-scenes.jsonl", all_candidates)
        batch_count = 0
        for batch_count, batch in enumerate(chunks(unique_pending, MAX_REVIEW_BATCH_SIZE), start=1):
            write_jsonl(review_dir / f"batch-{batch_count:04d}.jsonl", batch)

        summary = {
            "schema": SCHEMA_VERSION,
            "manifest_sha256": sha256_bytes(manifest.read_bytes()),
            "source_count": len(source_paths),
            "source_bytes": sum(int(row["bytes"]) for row in manifest_rows),
            "message_count": total_messages,
            "scene_candidate_count": len(all_candidates),
            "unique_pending_review_count": len(unique_pending),
            "exact_duplicate_count": duplicate_count,
            "review_batch_count": batch_count,
            "review_batch_size": MAX_REVIEW_BATCH_SIZE,
            "continuation_line_count": total_continuations,
            "skipped_prologue_line_count": total_skipped_prologue,
            "network_requests": 0,
            "ai_requests": 0,
        }
        write_json(reports_dir / "source-manifest.json", manifest_rows)
        write_json(reports_dir / "summary.json", summary)
        readme = temp / "README_PRIVATE.md"
        readme.write_text(
            "# NIA human dialogue private snapshot\n\n"
            "- This directory contains consented but private conversation text.\n"
            "- It is intentionally stored under ignored `data/private/` and must not be committed.\n"
            "- `sources/` contains exact read-only copies with neutral names.\n"
            "- `normalized/` preserves parsed message content and source line references; exact bytes remain in `sources/`.\n"
            "- `candidates/all-scenes.jsonl` contains every response-scene candidate.\n"
            "- `review/pending-batches/` contains unique candidates, 250 per batch.\n"
            "- Every candidate starts PENDING. Nothing is approved for production automatically.\n"
            "- Reviewers must reject private-context-dependent, unsafe, or non-NIA-style replies.\n",
            encoding="utf-8",
        )
        readme.chmod(0o600)
        temp.rename(output)
        output.chmod(0o700)
        return summary
    except Exception:
        shutil.rmtree(temp, ignore_errors=True)
        raise


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    summary = prepare(args.manifest.resolve(), args.output.resolve())
    print(
        "prepared private snapshot: "
        f"sources={summary['source_count']} messages={summary['message_count']} "
        f"candidates={summary['scene_candidate_count']} pending={summary['unique_pending_review_count']} "
        f"output={args.output.resolve()}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
