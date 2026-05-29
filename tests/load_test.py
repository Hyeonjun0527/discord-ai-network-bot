"""Concurrent request load test (requires a running Ollama instance).

Run manually:
    python tests/load_test.py
"""
from __future__ import annotations

import asyncio

from discord_assistant.llm import OllamaClient

_BASE_URL = "http://localhost:11434"
_MODEL = "llama3.1:8b"
_CONCURRENCY = 10


async def test_concurrent_10() -> None:
    """Send 10 concurrent requests to Ollama and report success/failure counts."""
    client = OllamaClient(
        base_url=_BASE_URL,
        default_model=_MODEL,
        timeout_seconds=120,
    )
    tasks = [client.generate("Hello, respond with one word.") for _ in range(_CONCURRENCY)]
    results = await asyncio.gather(*tasks, return_exceptions=True)
    errors = [r for r in results if isinstance(r, Exception)]
    successes = _CONCURRENCY - len(errors)
    print(f"Success: {successes}/{_CONCURRENCY}, Errors: {len(errors)}")
    for i, err in enumerate(errors, 1):
        print(f"  Error {i}: {err}")


if __name__ == "__main__":
    asyncio.run(test_concurrent_10())
