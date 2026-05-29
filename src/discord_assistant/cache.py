"""In-memory TTL cache for bot responses and translations."""
from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any

TRANSLATION_CACHE_TTL = 3600  # 1 hour
TRANSLATION_CACHE_MAX_SIZE = 500


@dataclass
class _Entry:
    value: Any
    expires_at: float


class TTLCache:
    def __init__(self, ttl: float = 60.0) -> None:
        self._store: dict[str, _Entry] = {}
        self.ttl = ttl

    def get(self, key: str) -> Any | None:
        entry = self._store.get(key)
        if entry is None or time.monotonic() > entry.expires_at:
            self._store.pop(key, None)
            return None
        return entry.value

    def set(self, key: str, value: Any) -> None:
        self._store[key] = _Entry(value=value, expires_at=time.monotonic() + self.ttl)

    def invalidate_prefix(self, prefix: str) -> None:
        # Match on a key-segment boundary so an exact key (e.g. "1:45") does not
        # also wipe sibling keys whose id merely shares the leading digits
        # (e.g. "1:456", "1:450"). A key matches when it equals the prefix or
        # extends it with the ":" segment separator used to build composite keys.
        sub_prefix = prefix + ":"
        keys = [
            k for k in self._store if k == prefix or k.startswith(sub_prefix)
        ]
        for k in keys:
            del self._store[k]

    def size(self) -> int:
        return len(self._store)

    def clear(self) -> None:
        self._store.clear()


summarize_cache = TTLCache(ttl=60.0)

# ---------------------------------------------------------------------------
# Translation cache (Phase 3 #38)
# (text, target_language) -> (translated_result, monotonic_timestamp)
# ---------------------------------------------------------------------------

_translation_cache: dict[tuple[str, str], tuple[str, float]] = {}


def get_translation(text: str, target_language: str) -> str | None:
    """Return a cached translation result if still within TTL, else None."""
    key = (text, target_language.lower())
    entry = _translation_cache.get(key)
    if entry is None:
        return None
    result, ts = entry
    if time.monotonic() - ts > TRANSLATION_CACHE_TTL:
        del _translation_cache[key]
        return None
    return result


def set_translation(text: str, target_language: str, result: str) -> None:
    """Store a translation result; evicts the oldest entry when cache is full."""
    if len(_translation_cache) >= TRANSLATION_CACHE_MAX_SIZE:
        oldest_key = min(_translation_cache, key=lambda k: _translation_cache[k][1])
        del _translation_cache[oldest_key]
    key = (text, target_language.lower())
    _translation_cache[key] = (result, time.monotonic())


def translation_cache_size() -> int:
    """Return the number of entries currently in the translation cache."""
    return len(_translation_cache)


def clear_translation_cache() -> None:
    """Remove all cached translations (useful for testing)."""
    _translation_cache.clear()


def purge_expired_translations() -> int:
    """Remove expired entries and return the count removed."""
    now = time.monotonic()
    expired = [k for k, (_, ts) in list(_translation_cache.items()) if now - ts > TRANSLATION_CACHE_TTL]
    for k in expired:
        del _translation_cache[k]
    return len(expired)
