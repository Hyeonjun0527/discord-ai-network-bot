#!/usr/bin/env python3
"""Synthetic regression tests for private Speech-style runtime export sanitization."""

from __future__ import annotations

import copy
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


def load_exporter() -> object:
    path = Path(__file__).with_name("export-human-speech-style-rag.py")
    spec = importlib.util.spec_from_file_location("human_speech_style_exporter", path)
    if spec is None or spec.loader is None:
        raise RuntimeError("private Speech-style exporter could not be loaded")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
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

    def test_current_v2_content_field_becomes_runtime_text(self) -> None:
        bubble = EXPORTER.to_bubble({"speaker": "지우", "content": "진짜?"})

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
        runtime = EXPORTER.to_runtime_record(card, 1, "a" * 64)

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

    def test_source_trace_must_match_the_card_range_exactly(self) -> None:
        card = approved_card()
        card["source_trace"][1]["ordinal"] = 3

        with self.assertRaisesRegex(ValueError, "does not match its range"):
            EXPORTER.validate_source_card(
                card,
                {"source-01": "a" * 64},
                {card["card_id"]: [passing_review(card["card_id"])]},
            )

    def test_runtime_excludes_a_pre_contract_card_instead_of_treating_it_as_importable(self) -> None:
        card = approved_card()
        card["privacy"] = {"runtime_import": "BLOCKED"}

        self.assertEqual("legacy_pre_runtime_contract", EXPORTER.runtime_exclusion_reason(card))

    def test_formal_export_requires_every_expected_source(self) -> None:
        expected_sources = {"source-01": "a" * 64, "source-02": "d" * 64}

        with self.assertRaisesRegex(ValueError, "does not cover every expected source"):
            EXPORTER.validate_complete_source_coverage(
                [approved_card()],
                expected_sources,
                EXPORTER.FORMAL_APPROVED_PROFILE,
            )

        second_card = copy.deepcopy(approved_card())
        second_card["card_id"] = "human-speech-card-000002"
        second_card["source_id"] = "source-02"
        EXPORTER.validate_complete_source_coverage(
            [approved_card(), second_card],
            expected_sources,
            EXPORTER.FORMAL_APPROVED_PROFILE,
        )

    def test_unknown_card_schema_is_rejected_not_silently_excluded(self) -> None:
        card = approved_card()
        card["schema"] = "unknown-card.v1"

        self.assertIsNone(EXPORTER.runtime_exclusion_reason(card))
        with self.assertRaisesRegex(ValueError, "schema is unsupported"):
            EXPORTER.validate_source_card(
                card,
                {"source-01": "a" * 64},
                {card["card_id"]: [passing_review(card["card_id"])]},
            )


class UserReleasedPreviewGateTest(unittest.TestCase):
    def test_user_released_preview_keeps_its_distinct_runtime_quality(self) -> None:
        card = preview_card()

        EXPORTER.validate_user_released_preview_card(card, {"source-01": "a" * 64})
        runtime = EXPORTER.to_runtime_record(
            card,
            1,
            "a" * 64,
            EXPORTER.USER_RELEASED_PREVIEW_PROFILE,
        )

        self.assertEqual("USER_RELEASED_REVIEW", runtime["quality"])
        self.assertEqual("2026-08-04-user-released-human-review", runtime["consent_revision"])
        self.assertFalse(runtime["response_surface_has_card_local_alias"])

    def test_card_local_alias_is_reduced_to_a_non_reversible_surface_safety_flag(self) -> None:
        card = preview_card()
        card["generalization"] = {"card_local_marker_aliases_used": ["가명"]}
        card["actual_human_reply"] = [{"speaker": "B", "content": "가명한테 물어봐"}]

        runtime = EXPORTER.to_runtime_record(
            card,
            1,
            "a" * 64,
            EXPORTER.USER_RELEASED_PREVIEW_PROFILE,
        )

        self.assertTrue(runtime["response_surface_has_card_local_alias"])
        self.assertNotIn("card_local_marker_aliases_used", runtime)

    def test_preview_without_its_original_safety_boundary_cannot_be_user_released(self) -> None:
        card = preview_card()
        card["privacy"]["runtime_import_blocked"] = False

        with self.assertRaisesRegex(ValueError, "pre-release safety boundary"):
            EXPORTER.validate_user_released_preview_card(card, {"source-01": "a" * 64})

    def test_user_released_export_requires_every_expected_source(self) -> None:
        expected_sources = {"source-01": "a" * 64, "source-02": "d" * 64}

        with self.assertRaisesRegex(ValueError, "does not cover every expected source"):
            EXPORTER.validate_complete_source_coverage(
                [preview_card()],
                expected_sources,
                EXPORTER.USER_RELEASED_PREVIEW_PROFILE,
            )

        second_card = copy.deepcopy(preview_card())
        second_card["card_id"] = "source-02-2024-01-card-01"
        second_card["source_id"] = "source-02"
        second_card["source_trace"]["source_sha256"] = "d" * 64
        EXPORTER.validate_complete_source_coverage(
            [preview_card(), second_card],
            expected_sources,
            EXPORTER.USER_RELEASED_PREVIEW_PROFILE,
        )

    def test_user_released_runtime_export_preserves_every_expected_source(self) -> None:
        expected_sources = {"source-01": "a" * 64, "source-02": "d" * 64}
        first_record = EXPORTER.to_runtime_record(
            preview_card(),
            1,
            "a" * 64,
            EXPORTER.USER_RELEASED_PREVIEW_PROFILE,
        )
        self.assertIsNotNone(first_record)

        with self.assertRaisesRegex(ValueError, "does not preserve every expected source"):
            EXPORTER.validate_complete_runtime_source_coverage(
                [first_record],
                expected_sources,
                EXPORTER.USER_RELEASED_PREVIEW_PROFILE,
            )

        second_record = copy.deepcopy(first_record)
        second_record["source_fingerprint"] = f"sha256:{'d' * 64}"
        EXPORTER.validate_complete_runtime_source_coverage(
            [first_record, second_record],
            expected_sources,
            EXPORTER.USER_RELEASED_PREVIEW_PROFILE,
        )


def approved_card() -> dict[str, object]:
    return {
        "schema": "nia-human-speech-card.v2",
        "card_id": "human-speech-card-000001",
        "status": "APPROVED",
        "decision": "STYLE_ONLY",
        "response_mode": "CARE",
        "situation": "힘든 상태를 부드럽게 챙기는 대화",
        "style_signals": ["짧은 돌봄"],
        "context_messages": [{"ordinal": 1, "speaker": "A", "text": "오늘 좀 힘들다", "content_sha256": "b" * 64}],
        "actual_human_reply": [{"ordinal": 2, "speaker": "B", "text": "푹 쉬어", "content_sha256": "c" * 64}],
        "combined_chars": 20,
        "source_id": "source-01",
        "context_start_ordinal": 1,
        "context_end_ordinal": 1,
        "reply_start_ordinal": 2,
        "reply_end_ordinal": 2,
        "source_trace": [
            {"ordinal": 1, "speaker": "A", "content_sha256": "b" * 64},
            {"ordinal": 2, "speaker": "B", "content_sha256": "c" * 64},
        ],
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


def preview_card() -> dict[str, object]:
    return {
        "schema": "nia-human-speech-card-preview.v1",
        "card_id": "source-01-2024-01-card-01",
        "status": "PLANNED_REQUIRES_FRESH_VERIFIER",
        "candidate_decision": "STYLE_ONLY",
        "response_mode": "CARE",
        "situation": "힘든 상태를 부드럽게 챙기는 대화",
        "style_signals": ["짧은 돌봄"],
        "context_messages": [{"speaker": "A", "content": "오늘 좀 힘들다"}],
        "actual_human_reply": [{"speaker": "B", "content": "푹 쉬어"}],
        "combined_chars": 20,
        "source_id": "source-01",
        "context_start_ordinal": 1,
        "context_end_ordinal": 1,
        "reply_start_ordinal": 2,
        "reply_end_ordinal": 2,
        "source_trace": {
            "source_sha256": "a" * 64,
            "records": [
                {"ordinal": 1, "source_speaker": "A", "content_sha256": "b" * 64},
                {"ordinal": 2, "source_speaker": "B", "content_sha256": "c" * 64},
            ],
        },
        "privacy": {
            "display_only_not_rag_input": True,
            "requires_fresh_verifier": True,
            "runtime_import_blocked": True,
        },
    }


if __name__ == "__main__":
    unittest.main()
