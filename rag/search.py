#!/usr/bin/env python3
"""Search AI Network RAG meta.db/corpus without external services."""
from __future__ import annotations

import argparse
import json
import sqlite3
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
RAG_DIR = Path(__file__).resolve().parent
META_DB = RAG_DIR / "meta.db"
CORPUS_JSONL = RAG_DIR / "bm25" / "corpus.jsonl"


def _matches_scope(item: dict, guild: int | None, space: int | None, channel: int | None) -> bool:
    metadata = item.get("metadata") or {}
    if guild is not None and metadata.get("guildId") != str(guild):
        return False
    if space is not None and metadata.get("knowledgeSpaceId") != str(space):
        return False
    if channel is not None and metadata.get("channelId") != str(channel):
        return False
    return True


def exact_matches(query: str, limit: int, guild: int | None = None, space: int | None = None, channel: int | None = None) -> list[dict]:
    conn = sqlite3.connect(META_DB)
    query_lc = query.lower()
    rows = conn.execute("SELECT DISTINCT chunk_id, identifier_lc FROM identifiers").fetchall()
    scores: dict[int, int] = {}
    for chunk_id, ident in rows:
        if ident and ident in query_lc:
            scores[chunk_id] = max(scores.get(chunk_id, 0), len(ident))
    result = []
    for chunk_id, score in sorted(scores.items(), key=lambda item: item[1], reverse=True)[:limit]:
        row = conn.execute(
            "SELECT id, source_file, chunk_type, title, content, metadata FROM chunks WHERE id=?",
            (chunk_id,),
        ).fetchone()
        if row:
            item = to_result(row, score + 1000)
            if _matches_scope(item, guild, space, channel):
                result.append(item)
    conn.close()
    return result


def keyword_matches(query: str, limit: int, guild: int | None = None, space: int | None = None, channel: int | None = None) -> list[dict]:
    terms = [term for term in query.lower().split() if len(term) >= 2]
    rows = []
    with CORPUS_JSONL.open(encoding="utf-8") as fp:
        for line in fp:
            item = json.loads(line)
            haystack = f"{item['title']} {item['content']}".lower()
            score = sum(haystack.count(term) for term in terms)
            if score and _matches_scope(item, guild, space, channel):
                item["score"] = score
                rows.append(item)
    return sorted(rows, key=lambda item: item["score"], reverse=True)[:limit]


def to_result(row, score: int) -> dict:
    return {
        "id": row[0],
        "source_file": row[1],
        "chunk_type": row[2],
        "title": row[3],
        "content": row[4],
        "metadata": json.loads(row[5]) if len(row) > 5 and row[5] else {},
        "score": score,
    }


def search(query: str, limit: int, guild: int | None = None, space: int | None = None, channel: int | None = None) -> list[dict]:
    if not META_DB.exists() or not CORPUS_JSONL.exists():
        raise SystemExit("RAG index missing. Run: python rag/build_index.py")
    merged: dict[int, dict] = {}
    for item in exact_matches(query, limit * 2, guild, space, channel) + keyword_matches(query, limit * 2, guild, space, channel):
        existing = merged.get(item["id"])
        if not existing or item["score"] > existing["score"]:
            merged[item["id"]] = item
    return sorted(merged.values(), key=lambda item: item["score"], reverse=True)[:limit]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("query")
    parser.add_argument("-k", "--limit", type=int, default=8)
    parser.add_argument("--guild", type=int, help="optional guild scope filter")
    parser.add_argument("--space", type=int, help="optional knowledge space scope filter")
    parser.add_argument("--channel", type=int, help="optional channel scope filter")
    args = parser.parse_args()
    for idx, item in enumerate(search(args.query, args.limit, args.guild, args.space, args.channel), 1):
        print("─" * 64)
        print(f"[{idx}] {item['title']} ({item['chunk_type']}, score={item['score']})")
        scope = item.get("metadata", {})
        scope_bits = [f"{k}={v}" for k, v in scope.items() if k in {"guildId", "channelId", "knowledgeSpaceId", "collection"} and v]
        print(f"    {item['source_file']}" + (f" · {' '.join(scope_bits)}" if scope_bits else ""))
        body = item["content"]
        print(body if len(body) <= 700 else body[:700] + " …")


if __name__ == "__main__":
    main()
