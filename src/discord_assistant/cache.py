"""In-memory TTL cache for bot responses and translations."""
from __future__ import annotations

import time
from collections import OrderedDict
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
# Backed by an OrderedDict so eviction is true LRU in O(1): the least-recently
# used entry is at the front, the most-recently used at the back.
# ---------------------------------------------------------------------------

_translation_cache: OrderedDict[tuple[str, str], tuple[str, float]] = OrderedDict()


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
    # Mark as most-recently used so popular entries survive eviction (LRU).
    _translation_cache.move_to_end(key)
    return result


def set_translation(text: str, target_language: str, result: str) -> None:
    """Store a translation result; evicts the least-recently-used entry when full.

    Refreshing an existing key never evicts another entry. When the cache is at
    capacity for a genuinely new key, expired entries are purged first and, only
    if still full, the least-recently-used entry (front of the OrderedDict) is
    dropped in O(1).
    """
    key = (text, target_language.lower())
    if key not in _translation_cache and len(_translation_cache) >= TRANSLATION_CACHE_MAX_SIZE:
        # Reclaim stale entries before evicting a live one, so expired items do
        # not push out valid entries.
        purge_expired_translations()
        if len(_translation_cache) >= TRANSLATION_CACHE_MAX_SIZE:
            _translation_cache.popitem(last=False)
    _translation_cache[key] = (result, time.monotonic())
    # Newly written / refreshed key becomes most-recently used.
    _translation_cache.move_to_end(key)


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
