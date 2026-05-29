"""Message normalization and prompt context building."""

from __future__ import annotations

import re
from collections.abc import Iterable, Sequence
from datetime import datetime, timezone

from .models import ChatMessage

_WHITESPACE_RE = re.compile(r"\s+")


def normalize_content(content: str) -> str:
    """Collapse whitespace and trim message text for transcript rendering."""

    return _WHITESPACE_RE.sub(" ", content).strip()


def _format_timestamp(created_at: datetime | None) -> str:
    if created_at is None:
        return "unknown-time"
    if created_at.tzinfo is None:
        created_at = created_at.replace(tzinfo=timezone.utc)
    return created_at.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M")


_MAX_SINGLE_MSG_CHARS = 500
_DEDUP_SIMILARITY_CHARS = 50  # Leading chars used for near-duplicate detection


def _truncate_long_message(content: str) -> str:
    """Truncate very long messages and append an indicator."""
    if len(content) > _MAX_SINGLE_MSG_CHARS:
        return content[:_MAX_SINGLE_MSG_CHARS] + "…[truncated]"
    return content


def filter_chat_messages(messages: Iterable[ChatMessage], *, include_bots: bool = False) -> list[ChatMessage]:
    """Remove bot, empty, and near-duplicate messages by default."""

    filtered: list[ChatMessage] = []
    seen_prefixes: set[str] = set()
    for message in messages:
        content = normalize_content(message.content)
        if not content:
            continue
        if message.is_bot and not include_bots:
            continue
        # Near-duplicate filter: skip messages with identical leading chars
        prefix = content[:_DEDUP_SIMILARITY_CHARS]
        if prefix in seen_prefixes:
            continue
        seen_prefixes.add(prefix)
        filtered.append(
            ChatMessage(
                author=message.author.strip() or "unknown",
                content=_truncate_long_message(content),
                created_at=message.created_at,
                is_bot=message.is_bot,
            )
        )
    return filtered


def build_transcript(
    messages: Sequence[ChatMessage],
    *,
    max_chars: int = 12_000,
    include_bots: bool = False,
) -> str:
    """Render messages as a chronological transcript and keep the newest content if trimmed."""

    if max_chars <= 0:
        raise ValueError("max_chars must be positive")

    filtered = filter_chat_messages(messages, include_bots=include_bots)
    lines = [
        f"[{_format_timestamp(message.created_at)}] {message.author}: {message.content}"
        for message in filtered
    ]

    selected_reversed: list[str] = []
    total = 0
    for line in reversed(lines):
        line_length = len(line) + (1 if selected_reversed else 0)
        if selected_reversed and total + line_length > max_chars:
            break
        if not selected_reversed and len(line) > max_chars:
            line = line[-max_chars:]
            line_length = len(line)
        selected_reversed.append(line)
        total += line_length

    return "\n".join(reversed(selected_reversed))


def from_discord_message(message: object) -> ChatMessage:
    """Convert a discord.py Message-like object into a ChatMessage.

    The function intentionally uses duck typing so tests do not need discord.py installed.
    """

    author = getattr(message, "author", None)
    author_name = (
        getattr(author, "display_name", None)
        or getattr(author, "global_name", None)
        or getattr(author, "name", None)
        or "unknown"
    )
    content = getattr(message, "clean_content", None) or getattr(message, "content", "") or ""

    attachments = getattr(message, "attachments", None) or []
    attachment_names = [getattr(attachment, "filename", "attachment") for attachment in attachments]
    if attachment_names:
        content = f"{content} [attachments: {', '.join(attachment_names)}]"

    return ChatMessage(
        author=str(author_name),
        content=content,
        created_at=getattr(message, "created_at", None),
        is_bot=bool(getattr(author, "bot", False)),
    )
