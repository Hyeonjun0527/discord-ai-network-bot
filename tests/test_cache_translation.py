"""Tests for the translation cache helpers in cache.py (#92)."""
from __future__ import annotations

import unittest
from unittest.mock import patch

from discord_assistant import cache
from discord_assistant.cache import (
    TRANSLATION_CACHE_MAX_SIZE,
    TRANSLATION_CACHE_TTL,
    TTLCache,
    clear_translation_cache,
    get_translation,
    purge_expired_translations,
    set_translation,
    translation_cache_size,
)


class TranslationCacheTest(unittest.TestCase):
    def setUp(self) -> None:
        clear_translation_cache()

    def tearDown(self) -> None:
        clear_translation_cache()

    def test_miss_returns_none(self) -> None:
        self.assertIsNone(get_translation("hello", "ko"))

    def test_set_then_get_round_trip(self) -> None:
        set_translation("hello", "ko", "안녕하세요")
        self.assertEqual(get_translation("hello", "ko"), "안녕하세요")

    def test_language_code_is_case_insensitive(self) -> None:
        set_translation("hello", "KO", "안녕하세요")
        self.assertEqual(get_translation("hello", "ko"), "안녕하세요")
        self.assertEqual(get_translation("hello", "Ko"), "안녕하세요")

    def test_different_target_language_is_separate_entry(self) -> None:
        set_translation("hello", "ko", "안녕하세요")
        set_translation("hello", "ja", "こんにちは")
        self.assertEqual(get_translation("hello", "ko"), "안녕하세요")
        self.assertEqual(get_translation("hello", "ja"), "こんにちは")

    def test_size_reflects_entries(self) -> None:
        self.assertEqual(translation_cache_size(), 0)
        set_translation("a", "ko", "1")
        set_translation("b", "ko", "2")
        self.assertEqual(translation_cache_size(), 2)

    def test_clear_empties_cache(self) -> None:
        set_translation("a", "ko", "1")
        clear_translation_cache()
        self.assertEqual(translation_cache_size(), 0)

    def test_expired_entry_returns_none(self) -> None:
        # Freeze monotonic so we control the timestamps deterministically.
        with patch.object(cache.time, "monotonic", return_value=1000.0):
            set_translation("hello", "ko", "안녕하세요")
        # Jump past the TTL window.
        with patch.object(cache.time, "monotonic", return_value=1000.0 + TRANSLATION_CACHE_TTL + 1):
            self.assertIsNone(get_translation("hello", "ko"))

    def test_purge_expired_removes_only_stale(self) -> None:
        with patch.object(cache.time, "monotonic", return_value=0.0):
            set_translation("old", "ko", "v1")
        with patch.object(cache.time, "monotonic", return_value=TRANSLATION_CACHE_TTL + 10):
            set_translation("fresh", "ko", "v2")
            removed = purge_expired_translations()
        self.assertEqual(removed, 1)
        self.assertEqual(translation_cache_size(), 1)

    def test_eviction_when_full(self) -> None:
        # Fill to capacity with monotonically increasing timestamps so the
        # oldest entry is well-defined, then overflow by one.
        for i in range(TRANSLATION_CACHE_MAX_SIZE):
            with patch.object(cache.time, "monotonic", return_value=float(i)):
                set_translation(f"text-{i}", "ko", f"result-{i}")
        self.assertEqual(translation_cache_size(), TRANSLATION_CACHE_MAX_SIZE)
        with patch.object(cache.time, "monotonic", return_value=float(TRANSLATION_CACHE_MAX_SIZE)):
            set_translation("overflow", "ko", "new")
        # Size stays at the cap and the oldest entry was evicted.
        self.assertEqual(translation_cache_size(), TRANSLATION_CACHE_MAX_SIZE)
        with patch.object(cache.time, "monotonic", return_value=float(TRANSLATION_CACHE_MAX_SIZE)):
            self.assertIsNone(get_translation("text-0", "ko"))
            self.assertEqual(get_translation("overflow", "ko"), "new")


class TTLCacheInvalidatePrefixTest(unittest.TestCase):
    def test_invalidate_prefix_exact_key_does_not_wipe_sibling_ids(self) -> None:
        # Keys are "{guild}:{channel}"; invalidating channel 45 must not also
        # drop channels whose id merely shares the leading digits (456, 450).
        c = TTLCache(ttl=60.0)
        c.set("1:45", "a")
        c.set("1:456", "b")
        c.set("1:450", "c")
        c.invalidate_prefix("1:45")
        self.assertIsNone(c.get("1:45"))
        self.assertEqual(c.get("1:456"), "b")
        self.assertEqual(c.get("1:450"), "c")

    def test_invalidate_prefix_matches_subkeyed_extension(self) -> None:
        # A composite key extending the prefix with the ":" separator is matched.
        c = TTLCache(ttl=60.0)
        c.set("1:45", "a")
        c.set("1:45:ko", "b")
        c.invalidate_prefix("1:45")
        self.assertIsNone(c.get("1:45"))
        self.assertIsNone(c.get("1:45:ko"))

    def test_invalidate_prefix_does_not_match_other_guild(self) -> None:
        c = TTLCache(ttl=60.0)
        c.set("1:45", "a")
        c.set("12:45", "b")
        c.invalidate_prefix("1:45")
        self.assertIsNone(c.get("1:45"))
        self.assertEqual(c.get("12:45"), "b")


if __name__ == "__main__":
    unittest.main()
