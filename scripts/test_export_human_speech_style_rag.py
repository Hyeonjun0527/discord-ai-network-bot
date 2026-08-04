#!/usr/bin/env python3
"""Synthetic regression tests for private Speech-style runtime export sanitization."""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


def load_exporter() -> object:
    path = Path(__file__).with_name("export-human-speech-style-rag.py")
    spec = importlib.util.spec_from_file_location("human_speech_style_exporter", path)
    if spec is None or spec.loader is None:
        raise RuntimeError("private Speech-style exporter could not be loaded")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


EXPORTER = load_exporter()


class RuntimeBubbleSanitizationTest(unittest.TestCase):
    def test_event_metadata_is_removed_and_machine_speakers_become_aliases(self) -> None:
        bubble = EXPORTER.to_bubble({"speaker": "A", "text": "좋다\n❤️참여자 B\n[반응 시점]"})

        self.assertEqual({"speaker": "서진", "text": "좋다"}, bubble)

    def test_media_only_bubble_is_not_exported(self) -> None:
        bubble = EXPORTER.to_bubble({"speaker": "B", "text": "[외부 링크]"})

        self.assertIsNone(bubble)

    def test_text_next_to_metadata_is_preserved(self) -> None:
        bubble = EXPORTER.to_bubble({"speaker": "지우", "text": "진짜?\n[외부 링크]"})

        self.assertEqual({"speaker": "지우", "text": "진짜?"}, bubble)


if __name__ == "__main__":
    unittest.main()
