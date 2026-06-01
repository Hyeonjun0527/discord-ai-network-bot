#!/usr/bin/env python3
"""Evaluate AI Network RAG retrieval against a small golden set."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

RAG_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAG_DIR))
GOLDEN = Path(__file__).resolve().parent / "golden.json"
EVAL_K = 10


def first_hit_rank(results: list[dict], expected_sources: list[str]) -> int | None:
    for rank, item in enumerate(results, 1):
        source = item.get("source_file", "")
        if any(expected in source for expected in expected_sources):
            return rank
    return None


def recall_at_k(results: list[dict], expected_sources: list[str]) -> float:
    found = {item.get("source_file", "") for item in results}
    hits = sum(1 for expected in expected_sources if any(expected in source for source in found))
    return hits / len(expected_sources) if expected_sources else 0.0


def main() -> None:
    parser = argparse.ArgumentParser(description="AI Network RAG retrieval eval")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()

    from search import search

    cases = json.loads(GOLDEN.read_text(encoding="utf-8"))["cases"]
    hits = {1: 0, 3: 0, 5: 0, 10: 0}
    mrr_sum = 0.0
    recall_sum = 0.0
    failures = []

    for case in cases:
        results = search(case["question"], EVAL_K, require_guild=False)
        rank = first_hit_rank(results, case["expected_sources"])
        recall_sum += recall_at_k(results, case["expected_sources"])
        if rank is None:
            failures.append(case)
        else:
            mrr_sum += 1.0 / rank
            for k in hits:
                if rank <= k:
                    hits[k] += 1
        if args.verbose:
            top = results[0].get("source_file", "-") if results else "-"
            print(f"{case['id']}: rank={rank or 'MISS'} top1={top}")

    n = len(cases)
    for k in (1, 3, 5, 10):
        print(f"Hit@{k}: {hits[k]}/{n} ({hits[k] / n * 100:.1f}%)")
    print(f"MRR: {mrr_sum / n:.4f}")
    print(f"Recall@10: {recall_sum / n:.4f}")
    if failures:
        for failure in failures:
            print(f"MISS {failure['id']}: {failure['question']} expected={failure['expected_sources']}", file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
