#!/usr/bin/env python3
"""Discord Assistant AI Network RAG index builder.

Dailyting RAG 스택을 이식하기 위한 안전한 1차 빌더다.
기본 실행은 외부 API 없이 docs/plans/ai-network Markdown 을 meta.db + BM25 corpus 로 색인한다.
OPENAI_API_KEY 와 QDRANT_URL 이 있고 --with-vector 를 주면 Qdrant 벡터 업서트를 수행한다.
"""
from __future__ import annotations

import argparse
import json
import os
import sqlite3
from pathlib import Path

try:
    from dotenv import load_dotenv
except ModuleNotFoundError:
    def load_dotenv(*_args, **_kwargs):
        return False

import chunkers

PROJECT_ROOT = Path(__file__).resolve().parent.parent
RAG_DIR = Path(__file__).resolve().parent
META_DB = RAG_DIR / "meta.db"
BM25_DIR = RAG_DIR / "bm25"
CORPUS_JSONL = BM25_DIR / "corpus.jsonl"
DEFAULT_COLLECTION = os.environ.get("AI_NETWORK_RAG_COLLECTION", "discord_ai_network")
DEFAULT_EMBED_MODEL = os.environ.get("AI_NETWORK_RAG_EMBED_MODEL", "text-embedding-3-large")

DEFAULT_INPUTS = (
    PROJECT_ROOT / "docs",
)


def collect_chunks(paths: list[Path], scope_metadata: dict[str, str] | None = None) -> list[chunkers.Chunk]:
    chunks: list[chunkers.Chunk] = []
    scope_metadata = {k: v for k, v in (scope_metadata or {}).items() if v not in (None, "")}
    for base in paths:
        if not base.exists():
            continue
        files = [base] if base.is_file() else sorted(base.rglob("*.md"))
        for path in files:
            if ".git" in path.parts:
                continue
            parsed = [
                chunkers.Chunk(
                    source_file=chunk.source_file,
                    chunk_type=chunk.chunk_type,
                    title=chunk.title,
                    content=chunk.content,
                    embedding_text=chunk.embedding_text,
                    metadata={**chunk.metadata, **scope_metadata},
                )
                for chunk in chunkers.parse_markdown(path, PROJECT_ROOT)
            ]
            chunks.extend(parsed)
            print(f"  {path.relative_to(PROJECT_ROOT)}: {len(parsed)} chunks")
    return chunks


def build_meta_db(chunks: list[chunkers.Chunk]) -> None:
    if META_DB.exists():
        META_DB.unlink()
    conn = sqlite3.connect(META_DB)
    conn.executescript(
        """
        CREATE TABLE chunks (
            id INTEGER PRIMARY KEY,
            source_file TEXT NOT NULL,
            chunk_type TEXT NOT NULL,
            title TEXT NOT NULL,
            content TEXT NOT NULL,
            embedding_text TEXT NOT NULL,
            metadata TEXT NOT NULL
        );
        CREATE TABLE identifiers (
            chunk_id INTEGER NOT NULL,
            identifier TEXT NOT NULL,
            identifier_lc TEXT NOT NULL
        );
        CREATE INDEX idx_ident ON identifiers(identifier_lc);
        CREATE INDEX idx_chunks_source ON chunks(source_file);
        CREATE INDEX idx_chunks_type ON chunks(chunk_type);
        """,
    )
    for idx, chunk in enumerate(chunks, 1):
        conn.execute(
            "INSERT INTO chunks VALUES (?,?,?,?,?,?,?)",
            (
                idx,
                chunk.source_file,
                chunk.chunk_type,
                chunk.title,
                chunk.content,
                chunk.embedding_text,
                json.dumps(chunk.metadata, ensure_ascii=False, sort_keys=True),
            ),
        )
        for identifier in chunkers.chunk_identifiers(chunk):
            conn.execute(
                "INSERT INTO identifiers VALUES (?,?,?)",
                (idx, identifier, identifier.lower()),
            )
    conn.commit()
    conn.close()


def build_keyword_corpus(chunks: list[chunkers.Chunk]) -> None:
    BM25_DIR.mkdir(parents=True, exist_ok=True)
    with CORPUS_JSONL.open("w", encoding="utf-8") as fp:
        for idx, chunk in enumerate(chunks, 1):
            fp.write(
                json.dumps(
                    {
                        "id": idx,
                        "source_file": chunk.source_file,
                        "chunk_type": chunk.chunk_type,
                        "title": chunk.title,
                        "content": chunk.content,
                        "metadata": chunk.metadata,
                    },
                    ensure_ascii=False,
                )
                + "\n",
            )


def build_vectors(
    chunks: list[chunkers.Chunk],
    collection: str,
    embedding_model: str,
    force: bool,
) -> None:
    api_key = os.environ.get("OPENAI_API_KEY")
    qdrant_url = os.environ.get("QDRANT_URL", "http://localhost:6333")
    if not api_key:
        raise SystemExit("OPENAI_API_KEY missing. Re-run without --with-vector or set the key.")

    from llama_index.core import StorageContext, VectorStoreIndex
    from llama_index.core.schema import TextNode
    from llama_index.embeddings.openai import OpenAIEmbedding
    from llama_index.vector_stores.qdrant import QdrantVectorStore
    from qdrant_client import QdrantClient

    embed_model = OpenAIEmbedding(model=embedding_model, api_key=api_key)
    nodes = []
    for idx, chunk in enumerate(chunks, 1):
        node = TextNode(
            text=chunk.content,
            metadata={
                "chunk_id": idx,
                "source_file": chunk.source_file,
                "chunk_type": chunk.chunk_type,
                "title": chunk.title,
                **chunk.metadata,
            },
        )
        nodes.append(node)
    vectors = embed_model.get_text_embedding_batch([c.embedding_text for c in chunks], show_progress=True)
    for node, vector in zip(nodes, vectors):
        node.embedding = vector

    client = QdrantClient(url=qdrant_url)
    if client.collection_exists(collection):
        if not force:
            raise SystemExit(f"collection already exists: {collection}. Use --force to rebuild it.")
        client.delete_collection(collection)
    vector_store = QdrantVectorStore(client=client, collection_name=collection)
    storage_context = StorageContext.from_defaults(vector_store=vector_store)
    VectorStoreIndex(nodes=nodes, storage_context=storage_context, embed_model=embed_model)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--with-vector", action="store_true", help="also rebuild Qdrant vector index")
    parser.add_argument("--input", action="append", default=[], help="file or directory to index")
    parser.add_argument("--guild", type=int, help="guild scope for payload filter metadata")
    parser.add_argument("--space", type=int, help="knowledge space scope for payload filter metadata")
    parser.add_argument("--channel", type=int, help="channel scope for payload filter metadata")
    parser.add_argument("--collection", default=DEFAULT_COLLECTION, help="Qdrant collection name")
    parser.add_argument("--embedding-model", default=DEFAULT_EMBED_MODEL, help="embedding model name")
    parser.add_argument("--force", action="store_true", help="delete and rebuild an existing Qdrant collection")
    args = parser.parse_args()

    load_dotenv(PROJECT_ROOT / ".env")
    load_dotenv(PROJECT_ROOT / ".env.local", override=True)

    inputs = [Path(p).resolve() for p in args.input] if args.input else list(DEFAULT_INPUTS)
    scope_metadata = {
        "guildId": str(args.guild) if args.guild is not None else "",
        "knowledgeSpaceId": str(args.space) if args.space is not None else "",
        "channelId": str(args.channel) if args.channel is not None else "",
        "collection": args.collection,
        "embeddingModel": args.embedding_model,
    }
    print("1) collect chunks")
    chunks = collect_chunks(inputs, scope_metadata)
    print(f"   total={len(chunks)}")
    if not chunks:
        raise SystemExit("no chunks collected")

    print("2) build meta.db")
    build_meta_db(chunks)
    print(f"   {META_DB}")

    print("3) build keyword corpus")
    build_keyword_corpus(chunks)
    print(f"   {CORPUS_JSONL}")

    if args.with_vector:
        print("4) build Qdrant vectors")
        build_vectors(chunks, args.collection, args.embedding_model, args.force)
    else:
        print("4) skip Qdrant vectors (--with-vector not set)")

    print("done")


if __name__ == "__main__":
    main()
