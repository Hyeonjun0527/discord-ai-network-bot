#!/usr/bin/env python3
"""AI Network RAG chunking helpers.

Dailyting RAG 의 핵심 원칙을 가져온다:
- 안정적인 SSOT 문서만 입력으로 삼는다.
- 표시 본문과 embedding 본문을 분리한다.
- chunk_id/source/title/type/identifiers 를 메타 DB에 남긴다.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
import re


@dataclass(frozen=True)
class Chunk:
    source_file: str
    chunk_type: str
    title: str
    content: str
    embedding_text: str
    metadata: dict[str, str] = field(default_factory=dict)


_HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*$")
_IDENTIFIER = re.compile(r"\b(?:REQ|ADR|Phase|Release)[-A-Za-z0-9_]*\d[-A-Za-z0-9_]*\b")


def parse_markdown(path: Path, project_root: Path) -> list[Chunk]:
    rel = str(path.relative_to(project_root))
    text = path.read_text(encoding="utf-8")
    sections: list[tuple[str, list[str]]] = []
    current_title = path.stem
    current_lines: list[str] = []

    for line in text.splitlines():
        match = _HEADING.match(line)
        if match and current_lines:
            sections.append((current_title, current_lines))
            current_title = match.group(2).strip()
            current_lines = [line]
        elif match:
            current_title = match.group(2).strip()
            current_lines = [line]
        else:
            current_lines.append(line)
    if current_lines:
        sections.append((current_title, current_lines))

    chunks: list[Chunk] = []
    for index, (title, lines) in enumerate(sections, 1):
        content = "\n".join(lines).strip()
        if not content:
            continue
        chunk_type = _chunk_type(rel, title, content)
        embedding_text = f"문서: {rel}\n섹션: {title}\n유형: {chunk_type}\n\n{content}"
        chunks.append(
            Chunk(
                source_file=rel,
                chunk_type=chunk_type,
                title=title,
                content=content,
                embedding_text=embedding_text,
                metadata={"section_index": str(index)},
            ),
        )
    return chunks


def chunk_identifiers(chunk: Chunk) -> list[str]:
    candidates = {chunk.title, chunk.source_file, chunk.chunk_type}
    candidates.update(_IDENTIFIER.findall(chunk.content))
    for token in ("채널 AI", "AI 네트워크", "프리셋", "다중 응답", "과부하", "지식", "대시보드"):
        if token in chunk.content or token in chunk.title:
            candidates.add(token)
    return sorted(c for c in candidates if c and len(c) >= 2)


def _chunk_type(rel: str, title: str, content: str) -> str:
    joined = f"{rel}\n{title}\n{content}".lower()
    if "rag" in joined or "knowledge" in joined or "지식" in joined:
        return "rag_knowledge"
    if "preset" in joined or "프리셋" in joined:
        return "preset_registry"
    if "multi" in joined or "다중 응답" in joined:
        return "multi_response"
    if "dashboard" in joined or "대시보드" in joined:
        return "dashboard"
    if "risk" in joined or "audit" in joined or "위험" in joined:
        return "risk_audit"
    if "domain" in joined or "aggregate" in joined or "도메인" in joined:
        return "domain_model"
    return "planning"
