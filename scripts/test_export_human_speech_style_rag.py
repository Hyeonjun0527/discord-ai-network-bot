#!/usr/bin/env python3
"""Synthetic regression tests for private Speech-style runtime export sanitization."""

from __future__ import annotations

import importlib.util
import json
import tempfile
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

    def test_inline_reaction_timestamp_is_removed_without_removing_the_reply(self) -> None:
        bubble = EXPORTER.to_bubble({"speaker": "지우", "text": "✌️ [반응 시점]"})

        self.assertEqual({"speaker": "지우", "text": "✌️"}, bubble)


class FormalApprovalGateTest(unittest.TestCase):
    def test_formally_approved_v2_card_with_fresh_pass_review_is_exportable(self) -> None:
        card = approved_card()

        EXPORTER.validate_source_card(
            card,
            {"source-01": "a" * 64},
            {card["card_id"]: [passing_review(card["card_id"])]},
        )
        runtime = EXPORTER.to_runtime_record(card, 1)

        self.assertEqual("CURATION_APPROVED", runtime["quality"])
        self.assertEqual("human-style-000001", runtime["example_id"])

    def test_preview_or_unapproved_card_cannot_cross_the_runtime_gate(self) -> None:
        card = approved_card()
        card["status"] = "PLANNED_REQUIRES_FRESH_VERIFIER"

        with self.assertRaisesRegex(ValueError, "not formally approved"):
            EXPORTER.validate_source_card(
                card,
                {"source-01": "a" * 64},
                {card["card_id"]: [passing_review(card["card_id"])]},
            )

    def test_latest_review_must_be_fresh_and_pass_every_criterion(self) -> None:
        card = approved_card()
        latest = passing_review(card["card_id"])
        latest["reviewed_at"] = "2026-08-05T00:00:00Z"
        latest["criteria"]["E"] = "FAIL"

        with self.assertRaisesRegex(ValueError, "does not pass every criterion"):
            EXPORTER.validate_source_card(
                card,
                {"source-01": "a" * 64},
                {card["card_id"]: [passing_review(card["card_id"]), latest]},
            )

    def test_curation_state_blocks_export_until_reaudit_is_finished(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            state_path = Path(directory) / "CURATION_STATE.json"
            state_path.write_text(
                json.dumps(
                    {
                        "style_rag_contract": {"pre_contract_approved_cards_reaudit_required": True},
                        "style_rag_reaudit": {"active_card_id": None},
                    },
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "re-audit is incomplete"):
                EXPORTER.validate_curation_state(state_path)


def approved_card() -> dict[str, object]:
    return {
        "schema": "nia-human-speech-card.v2",
        "card_id": "human-speech-card-000001",
        "status": "APPROVED",
        "decision": "STYLE_ONLY",
        "response_mode": "CARE",
        "situation": "힘든 상태를 부드럽게 챙기는 대화",
        "style_signals": ["짧은 돌봄"],
        "context_messages": [{"speaker": "A", "text": "오늘 좀 힘들다"}],
        "actual_human_reply": [{"speaker": "B", "text": "푹 쉬어"}],
        "combined_chars": 20,
        "source_id": "source-01",
        "source_trace": {"source_sha256": "a" * 64},
    }


def passing_review(card_id: str) -> dict[str, object]:
    return {
        "schema": "nia-human-speech-card-review.v1",
        "card_id": card_id,
        "review_id": f"{card_id}-review-001",
        "fresh_context": True,
        "criteria": {key: "PASS" for key in "ABCDEF"},
        "verdict": "PASS",
        "reviewed_at": "2026-08-04T00:00:00Z",
    }


if __name__ == "__main__":
    unittest.main()
