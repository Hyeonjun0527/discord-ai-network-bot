#!/usr/bin/env python3
"""private Speech-style runtime candidate materialization의 synthetic 회귀 테스트."""

from __future__ import annotations

import importlib.util
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


def load_materializer() -> object:
    path = Path(__file__).with_name("materialize-human-speech-style-runtime-index.py")
    spec = importlib.util.spec_from_file_location("human_speech_style_materializer", path)
    if spec is None or spec.loader is None:
        raise RuntimeError("private Speech-style materializer could not be loaded")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


MATERIALIZER = load_materializer()


class RuntimeCandidateMaterializationTest(unittest.TestCase):
    def test_response_move_and_form_are_optional_observation_metadata(self) -> None:
        card = runtime_card("FOLLOW_UP", context="아까 말한 일이 생각보다 오래 걸렸어", response="왜 그렇게 됐어?")

        self.assertEqual("FOLLOW_UP_CAUSE", MATERIALIZER.classify_response_move(card))
        self.assertEqual("QUESTION", MATERIALIZER.classify_response_form(card))

    def test_response_rhythm_is_closed_reusable_metadata_not_the_reply_text(self) -> None:
        card = runtime_card("ALIGNMENT", context="오늘 좀 답답하네", response="나도 너무 답답함", example_id="human-style-000003")

        self.assertEqual(
            ["AGREE_AND_ADD", "SHARED_FEELING", "SHORT_REPLY", "SINGLE_BUBBLE"],
            MATERIALIZER.classify_response_rhythm(card),
        )

    def test_response_rhythm_does_not_invent_a_mode_specific_cue_when_the_reply_has_no_evidence(self) -> None:
        card = runtime_card("SPECULATION", context="그 사람이 오늘 올까", response="ㅋㅋ", example_id="human-style-000004")

        self.assertEqual(["TINY_REPLY", "SINGLE_BUBBLE"], MATERIALIZER.classify_response_rhythm(card))

    def test_provider_style_cues_use_only_closed_response_form_and_rhythm_metadata(self) -> None:
        cases = (
            ("REACTION", "EXPRESSIVE", [], ["REACTION_IMMEDIATE"]),
            ("REACTION", None, ["LAUGHTER"], ["REACTION_LAUGH_ALONG"]),
            ("REACTION", None, ["POSITIVE_ACKNOWLEDGMENT"], ["REACTION_WARM_ACK"]),
            ("ALIGNMENT", "ALIGN_AND_ADD", [], ["ALIGNMENT_LOW_KEY_ACK"]),
            ("ALIGNMENT", None, ["SHARED_FEELING"], ["ALIGNMENT_SHARED_FEELING"]),
            ("PLAY", "PLAYFUL_RETURN", [], ["PLAY_COUNTERTEASE"]),
            ("PLAY", None, ["LIGHT_EXAGGERATION"], ["PLAY_LIGHT_EXAGGERATION"]),
            ("FOLLOW_UP", "QUESTION", [], ["FOLLOW_UP_SOFT_CHECK"]),
            ("FOLLOW_UP", None, ["DIRECT_QUESTION"], ["FOLLOW_UP_DIRECT_CHECK"]),
            ("SPECULATION", "HEDGED_GUESS", [], ["SPECULATION_LIGHT_HEDGE"]),
            ("CARE", "SUPPORTIVE", [], ["CARE_GENTLE_VALIDATE"]),
            ("CARE", None, ["SUPPORTIVE_NUDGE"], ["CARE_SOFT_NUDGE"]),
            ("COORDINATION", "PROPOSAL", [], ["COORDINATION_PROPOSE"]),
            ("COORDINATION", "QUESTION", [], ["COORDINATION_ASK_ONE"]),
            ("COORDINATION", None, ["COORDINATION_CHECK"], ["COORDINATION_CONFIRM"]),
        )

        for response_mode, response_form, response_rhythm, expected in cases:
            with self.subTest(response_mode=response_mode, expected=expected):
                self.assertEqual(
                    expected,
                    MATERIALIZER.classify_provider_style_cues(response_mode, response_form, response_rhythm),
                )

        self.assertEqual(
            ["REACTION_WARM_ACK"],
            MATERIALIZER.classify_provider_style_cues(
                "REACTION",
                "EXPRESSIVE",
                ["SHORT_REACTION", "LAUGHTER", "POSITIVE_ACKNOWLEDGMENT"],
            ),
        )
        precedence_cases = (
            (
                "ALIGNMENT",
                "ALIGN_AND_ADD",
                ["AGREE_AND_ADD", "SHARED_FEELING"],
                ["ALIGNMENT_SHARED_FEELING"],
            ),
            (
                "PLAY",
                "PLAYFUL_RETURN",
                ["PLAYFUL_RETURN", "LIGHT_EXAGGERATION"],
                ["PLAY_LIGHT_EXAGGERATION"],
            ),
            ("FOLLOW_UP", "QUESTION", ["DIRECT_QUESTION"], ["FOLLOW_UP_DIRECT_CHECK"]),
            ("CARE", "SUPPORTIVE", ["GENTLE_CARE", "SUPPORTIVE_NUDGE"], ["CARE_SOFT_NUDGE"]),
            ("COORDINATION", "QUESTION", ["ACTION_PROPOSAL"], ["COORDINATION_ASK_ONE"]),
            (
                "COORDINATION",
                "QUESTION",
                ["COORDINATION_CHECK", "ACTION_PROPOSAL"],
                ["COORDINATION_CONFIRM"],
            ),
        )
        for response_mode, response_form, response_rhythm, expected in precedence_cases:
            with self.subTest(response_mode=response_mode, expected=expected):
                self.assertEqual(
                    expected,
                    MATERIALIZER.classify_provider_style_cues(response_mode, response_form, response_rhythm),
                )
        self.assertEqual(
            [],
            MATERIALIZER.classify_provider_style_cues("CARE", None, ["TINY_REPLY", "SINGLE_BUBBLE"]),
        )

    def test_missing_provider_style_cue_keeps_an_otherwise_observed_card_audit_only(self) -> None:
        card = classified(
            runtime_card(
                "FOLLOW_UP",
                context="아까 말한 일이 생각보다 오래 걸렸어",
                response="왜 그렇게 됐어?",
                example_id="human-style-000029",
            ),
        )
        card["provider_style_cues"] = []

        prompt_surface, reasons = MATERIALIZER.determine_prompt_surface(card)

        self.assertEqual("AUDIT_ONLY", prompt_surface)
        self.assertEqual(["STYLE_PATTERN_MISSING_PROVIDER_STYLE_CUE"], reasons)

    def test_response_delivery_uses_only_closed_length_and_tone_cues(self) -> None:
        card = runtime_card("PLAY", context="가볍게 장난치는 대화", response="ㅇㅇ ㅠ..", example_id="human-style-000009")

        self.assertEqual(
            ["SHORT_REPLY", "TRAILING_PAUSE", "CASUAL_SHORT_FORM", "SOFT_EMOTION_MARKER", "SINGLE_BUBBLE"],
            MATERIALIZER.classify_response_rhythm(card),
        )

    def test_speculation_hedge_variants_are_observed_without_broad_matching(self) -> None:
        observed = runtime_card("SPECULATION", context="그 사람이 오늘 올까", response="그 사람인가?", example_id="human-style-000005")
        colloquial = runtime_card("SPECULATION", context="그 사람이 오늘 올까", response="올려나", example_id="human-style-000006")
        typo = runtime_card("SPECULATION", context="그 사람이 오늘 올까", response="그거 같ㅇ", example_id="human-style-000007")
        non_hedge = runtime_card("SPECULATION", context="그 사람이 오늘 올까", response="같은 반", example_id="human-style-000008")

        for card in (observed, colloquial, typo):
            self.assertEqual("HEDGED_GUESS", MATERIALIZER.classify_response_form(card))
            self.assertIn("HEDGED_GUESS", MATERIALIZER.classify_response_rhythm(card))
        self.assertIsNone(MATERIALIZER.classify_response_form(non_hedge))

    def test_response_move_prefers_the_actual_reply_and_only_resolves_short_coordination_ack_from_context(self) -> None:
        proposal = runtime_card("COORDINATION", context="어디 갈까?", response="같이 가자", example_id="human-style-000021")
        choice_ack = runtime_card("COORDINATION", context="어디 갈까?", response="ㅇㅋ", example_id="human-style-000022")
        role_ack = runtime_card("COORDINATION", context="누가 먼저 맡을래?", response="응", example_id="human-style-000023")

        self.assertEqual("COORDINATION_ACTION", MATERIALIZER.classify_response_move(proposal))
        self.assertEqual("COORDINATION_CHOICE", MATERIALIZER.classify_response_move(choice_ack))
        self.assertEqual("COORDINATION_ROLE", MATERIALIZER.classify_response_move(role_ack))

    def test_speculation_move_classification_distinguishes_cause_future_and_present(self) -> None:
        cause = runtime_card("SPECULATION", context="왜 아직 답이 없지?", response="바쁜가 봐", example_id="human-style-000024")
        future = runtime_card("SPECULATION", context="내일 올까?", response="올 것 같은데", example_id="human-style-000025")
        present = runtime_card("SPECULATION", context="지금 자고 있나?", response="그런가 봐", example_id="human-style-000026")

        self.assertEqual("SPECULATION_CAUSE", MATERIALIZER.classify_response_move(cause))
        self.assertEqual("SPECULATION_FUTURE", MATERIALIZER.classify_response_move(future))
        self.assertEqual("SPECULATION_PRESENT", MATERIALIZER.classify_response_move(present))

    def test_scene_traits_use_only_context_and_situation_and_stay_mode_compatible(self) -> None:
        response_only_physical = runtime_card(
            "CARE",
            context="별일 없었다고 했어",
            response="감기라 병원 다녀왔어",
            example_id="human-style-000027",
        )
        situation_physical = {
            **response_only_physical,
            "situation": "오늘 몸살 때문에 병원에 다녀온 장면",
        }
        crowded_coordination = runtime_card(
            "COORDINATION",
            context="오늘 저녁에 어디 갈지 몇 시에 정할까",
            response="상관없어",
            example_id="human-style-000028",
        )

        self.assertEqual([], MATERIALIZER.classify_scene_traits(response_only_physical))
        self.assertEqual(["CARE_PHYSICAL_CONDITION"], MATERIALIZER.classify_scene_traits(situation_physical))
        traits = MATERIALIZER.classify_scene_traits(crowded_coordination)
        self.assertEqual(2, len(traits))
        self.assertTrue(set(traits).issubset({"COORDINATION_CHOICE", "COORDINATION_TIME", "COORDINATION_ACTION_PROPOSAL", "COORDINATION_ROLE_OR_ORDER"}))

    def test_materialization_preserves_every_card_but_enables_only_observed_style_patterns(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path = root / "input.jsonl"
            output_dir = root / "output"
            input_path.write_text(
                "\n".join(
                    json.dumps(card, ensure_ascii=False)
                    for card in (
                        runtime_card("FOLLOW_UP", context="아까 말한 일이 생각보다 오래 걸렸어", response="왜 그렇게 됐어?", example_id="human-style-000001"),
                        runtime_card("CARE", context="오늘 하루가 너무 힘들고 지친다고 했어", response="ㅋㅋㅋㅋ", example_id="human-style-000002"),
                    )
                )
                + "\n",
                encoding="utf-8",
            )
            source_coverage_policy = write_source_coverage_policy(input_path)

            result = subprocess.run(
                [
                    sys.executable,
                    str(Path(MATERIALIZER.__file__)),
                    "--input-jsonl",
                    str(input_path),
                    "--output-dir",
                    str(output_dir),
                    "--source-coverage-policy",
                    str(source_coverage_policy),
                ],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            records = [json.loads(line) for line in (output_dir / "human-speech-style-cards.jsonl").read_text(encoding="utf-8").splitlines()]
            manifest = json.loads((output_dir / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(2, len(records))
            self.assertEqual("nia-human-speech-style-runtime-candidate-manifest.v8", manifest["schema"])
            self.assertEqual(1, manifest["expected_source_count"])
            self.assertTrue(manifest["source_coverage_complete"])
            self.assertEqual(
                ["nia-human-speech-style-import-card.v4", "nia-human-speech-style-import-card.v4"],
                [record["schema"] for record in records],
            )
            expected_card_keys = {
                "schema",
                "example_id",
                "response_mode",
                "situation",
                "style_signals",
                "context_bubbles",
                "response_bubbles",
                "quality",
                "source_fingerprint",
                "consent_revision",
                "combined_chars",
                "response_surface_has_card_local_alias",
                "embedding_model",
                "scene_traits",
                "response_move",
                "response_move_provenance",
                "response_form",
                "response_rhythm",
                "provider_style_cues",
                "prompt_surface",
                "prompt_eligible",
            }
            for record in records:
                self.assertEqual(expected_card_keys, set(record))
            self.assertEqual([True, False], [record["prompt_eligible"] for record in records])
            self.assertEqual(["FOLLOW_UP_CAUSE", None], [record["response_move"] for record in records])
            self.assertEqual(
                ["HEURISTIC_OBSERVED", "NONE"],
                [record["response_move_provenance"] for record in records],
            )
            self.assertEqual(["QUESTION", None], [record["response_form"] for record in records])
            self.assertEqual(
                [[], ["CARE_EMOTIONAL_DISTRESS"]],
                [record["scene_traits"] for record in records],
            )
            self.assertEqual(
                [["DIRECT_QUESTION", "SHORT_REPLY", "SINGLE_BUBBLE"], ["TINY_REPLY", "SINGLE_BUBBLE"]],
                [record["response_rhythm"] for record in records],
            )
            self.assertEqual(
                [["FOLLOW_UP_DIRECT_CHECK"], []],
                [record["provider_style_cues"] for record in records],
            )
            self.assertEqual(1, manifest["prompt_eligible_count"])
            self.assertEqual(1, manifest["prompt_disabled_count"])
            self.assertEqual({"FOLLOW_UP": 1}, manifest["prompt_eligible_by_response_mode"])
            self.assertEqual(
                {
                    "STYLE_PATTERN_ENUM_ACT_NOT_VISIBLE": 1,
                    "STYLE_PATTERN_LOW_SIGNAL_RESPONSE_RHYTHM": 1,
                    "STYLE_PATTERN_MISSING_REUSABLE_RESPONSE_FORM": 1,
                    "STYLE_PATTERN_MISSING_PROVIDER_STYLE_CUE": 1,
                },
                manifest["prompt_ineligible_reason_counts"],
            )
            self.assertEqual({"FOLLOW_UP_CAUSE": 1}, manifest["response_move_metadata_counts"])
            self.assertEqual(
                {"CARE_EMOTIONAL_DISTRESS": 1},
                manifest["scene_trait_counts"],
            )
            self.assertEqual(
                {"FOLLOW_UP_DIRECT_CHECK": 1},
                manifest["provider_style_cue_counts"],
            )
            self.assertEqual(
                {"HEURISTIC_OBSERVED": 1, "NONE": 1},
                manifest["response_move_provenance_counts"],
            )
            self.assertEqual({"QUESTION": 1}, manifest["response_form_metadata_counts"])
            self.assertEqual(2, manifest["response_rhythm_coverage"])
            self.assertEqual(1, manifest["response_rhythm_behavior_coverage"])
            self.assertEqual(1, manifest["response_rhythm_delivery_only_count"])
            self.assertEqual(
                {"DIRECT_QUESTION": 1, "SHORT_REPLY": 1, "SINGLE_BUBBLE": 2, "TINY_REPLY": 1},
                manifest["response_rhythm_cue_counts"],
            )
            self.assertEqual(
                "closed_metadata_response_mode_then_semantic_tie_primary_style_cue_contrast_rerank_v11",
                manifest["retrieval_policy"],
            )
            self.assertEqual("observed_response_metadata_with_fresh_review_overlay_v1", manifest["response_move_policy"])
            self.assertIsNone(manifest["response_move_review_ledger_sha256"])
            self.assertEqual(0, manifest["fresh_verified_response_move_count"])
            self.assertEqual(1, manifest["heuristically_observed_response_move_count"])
            self.assertEqual(0, manifest["rejected_response_move_review_count"])
            self.assertEqual("closed_style_pattern_v1", manifest["prompt_surface_policy"])
            self.assertEqual(["STYLE_PATTERN", "AUDIT_ONLY"], [record["prompt_surface"] for record in records])
            self.assertEqual(0o700, output_dir.stat().st_mode & 0o777)
            self.assertEqual(0o600, (output_dir / "human-speech-style-cards.jsonl").stat().st_mode & 0o777)

    def test_unexpected_raw_like_field_is_rejected_before_any_candidate_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path = root / "input.jsonl"
            output_dir = root / "output"
            input_path.write_text(
                "\n".join(
                    json.dumps(card, ensure_ascii=False)
                    for card in (
                        runtime_card(
                            "FOLLOW_UP",
                            context="synthetic context",
                            response="synthetic response",
                            example_id="human-style-000101",
                        ),
                        {
                            **runtime_card(
                                "FOLLOW_UP",
                                context="synthetic context",
                                response="synthetic response",
                                example_id="human-style-000102",
                            ),
                            "raw_dialogue": "synthetic-only",
                        },
                    )
                )
                + "\n",
                encoding="utf-8",
            )

            result = subprocess.run(
                [
                    sys.executable,
                    str(Path(MATERIALIZER.__file__)),
                    "--input-jsonl",
                    str(input_path),
                    "--output-dir",
                    str(output_dir),
                ],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("card fields are invalid", result.stderr)
            self.assertFalse(output_dir.exists())

    def test_materialization_rejects_an_input_that_does_not_cover_the_required_source_set(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path = root / "input.jsonl"
            output_dir = root / "output"
            input_path.write_text(
                json.dumps(
                    runtime_card(
                        "FOLLOW_UP",
                        context="synthetic context",
                        response="synthetic response",
                        example_id="human-style-000103",
                    ),
                    ensure_ascii=False,
                )
                + "\n",
                encoding="utf-8",
            )
            required_fingerprints = {
                "sha256:" + "a" * 64,
                "sha256:" + "b" * 64,
            }
            policy_path = root / "required-source-coverage-policy.json"
            policy_path.write_text(
                json.dumps(
                    {
                        "schema": "nia-human-speech-style-source-coverage.v1",
                        "source_count": len(required_fingerprints),
                        "source_fingerprint_set_sha256": hashlib.sha256(
                            "\n".join(sorted(required_fingerprints)).encode(),
                        ).hexdigest(),
                    },
                ),
                encoding="utf-8",
            )

            result = subprocess.run(
                [
                    sys.executable,
                    str(Path(MATERIALIZER.__file__)),
                    "--input-jsonl",
                    str(input_path),
                    "--output-dir",
                    str(output_dir),
                    "--source-coverage-policy",
                    str(policy_path),
                ],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("does not match required source coverage count", result.stderr)
            self.assertFalse(output_dir.exists())

    def test_prompt_eligibility_uses_the_generalized_context_response_pair_and_rejects_explicit_private_markers(self) -> None:
        safe = runtime_card(
            "FOLLOW_UP",
            context="아까 말한 일이 생각보다 오래 걸렸어",
            response="왜 그렇게 됐어?",
            example_id="human-style-000009",
        )
        marked = runtime_card(
            "FOLLOW_UP",
            context="[private-context-marker] 이 앞 대화는 provider prompt에 보이면 안 된다",
            response="왜 그렇게 됐어?",
            example_id="human-style-000010",
        )

        self.assertEqual([], MATERIALIZER.prompt_ineligibility_reasons(classified(safe)))
        self.assertIn("MEDIA_OR_SYSTEM_MARKER", MATERIALIZER.prompt_ineligibility_reasons(classified(marked)))

    def test_prompt_eligibility_keeps_contextual_alignment_but_rejects_self_focused_care(self) -> None:
        bare_alignment = classified(runtime_card("ALIGNMENT", context="오늘 하루 종일 너무 답답하고 지치네", response="나도", example_id="human-style-000011"))
        self_focused_care = classified(runtime_card("CARE", context="오늘 하루가 너무 힘들고 지친다고 했어", response="나도 너무 피곤해", example_id="human-style-000012"))

        self.assertEqual([], MATERIALIZER.prompt_ineligibility_reasons(bare_alignment))
        self.assertIn("ENUM_ACT_NOT_VISIBLE", MATERIALIZER.prompt_ineligibility_reasons(self_focused_care))

    def test_prompt_eligibility_keeps_short_standalone_care_and_actionable_coordination(self) -> None:
        care = classified(runtime_card("CARE", context="오늘 하루가 너무 힘들고 지친다고 했어", response="푹 쉬어", example_id="human-style-000013"))
        coordination = classified(runtime_card("COORDINATION", context="오늘 저녁에 어디 갈지 같이 정해 보자", response="같이 가자", example_id="human-style-000014"))

        self.assertEqual([], MATERIALIZER.prompt_ineligibility_reasons(care))
        self.assertEqual([], MATERIALIZER.prompt_ineligibility_reasons(coordination))

    def test_prompt_eligibility_allows_card_local_aliases_in_generalized_pair_but_rejects_direct_identifier(self) -> None:
        local_alias = classified(
            {
                **runtime_card("FOLLOW_UP", context="민서가 아까 이상한 일을 겪었다고 했어", response="왜 그랬어?", example_id="human-style-000015"),
                "response_surface_has_card_local_alias": True,
            },
        )
        identifier = classified(
            runtime_card("FOLLOW_UP", context="전화번호 010-1234-5678을 남겼다고 했어", response="왜 그렇게 됐어?", example_id="human-style-000016"),
        )

        self.assertEqual([], MATERIALIZER.prompt_ineligibility_reasons(local_alias))
        self.assertIn("IDENTIFIER_OR_NUMBER", MATERIALIZER.prompt_ineligibility_reasons(identifier))

    def test_prompt_eligibility_keeps_short_coordination_ack_as_audit_only_without_an_observed_pattern(self) -> None:
        contextual_ack = classified(
            runtime_card("COORDINATION", context="오늘 저녁에 같이 밥 먹으러 갈까", response="ㅇㅋ", example_id="human-style-000017"),
        )
        detached_ack = classified(
            runtime_card("COORDINATION", context="그냥 오늘 하루가 길게 느껴진다", response="ㅇㅋ", example_id="human-style-000018"),
        )

        contextual_reasons = MATERIALIZER.prompt_ineligibility_reasons(contextual_ack)
        detached_reasons = MATERIALIZER.prompt_ineligibility_reasons(detached_ack)

        self.assertIn("MISSING_REUSABLE_RESPONSE_FORM", contextual_reasons)
        self.assertIn("LOW_SIGNAL_RESPONSE_RHYTHM", contextual_reasons)
        self.assertIn("ENUM_ACT_NOT_VISIBLE", detached_reasons)

    def test_fresh_verifier_ledger_is_bound_to_source_overrides_heuristic_moves_and_keeps_unreviewed_observations(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path = root / "input.jsonl"
            ledger_path = root / "move-reviews.json"
            output_dir = root / "output"
            cards = [
                runtime_card(
                    "FOLLOW_UP",
                    context="아까 말한 일이 생각보다 오래 걸렸어",
                    response="왜 그렇게 됐어?",
                    example_id="human-style-000031",
                ),
                runtime_card(
                    "FOLLOW_UP",
                    context="아까 말한 일이 생각보다 오래 걸렸어",
                    response="왜 그렇게 됐어?",
                    example_id="human-style-000032",
                ),
                runtime_card(
                    "FOLLOW_UP",
                    context="아까 말한 일이 생각보다 오래 걸렸어",
                    response="왜 그렇게 됐어?",
                    example_id="human-style-000033",
                ),
            ]
            input_path.write_text("\n".join(json.dumps(card, ensure_ascii=False) for card in cards) + "\n", encoding="utf-8")
            source_digest = hashlib.sha256(input_path.read_bytes()).hexdigest()
            ledger = {
                "schema": "nia-human-speech-style-response-move-review-ledger.v1",
                "input_jsonl_sha256": source_digest,
                "reviews": [
                    fresh_review(cards[0], "PASS", "FOLLOW_UP_CAUSE"),
                    fresh_review(cards[1], "REJECT", None, actual_response_action="FAIL"),
                ],
            }
            ledger_path.write_text(json.dumps(ledger, ensure_ascii=False), encoding="utf-8")
            source_coverage_policy = write_source_coverage_policy(input_path)

            result = subprocess.run(
                [
                    sys.executable,
                    str(Path(MATERIALIZER.__file__)),
                    "--input-jsonl",
                    str(input_path),
                    "--output-dir",
                    str(output_dir),
                    "--source-coverage-policy",
                    str(source_coverage_policy),
                    "--response-move-review-ledger",
                    str(ledger_path),
                ],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            records = [json.loads(line) for line in (output_dir / "human-speech-style-cards.jsonl").read_text(encoding="utf-8").splitlines()]
            manifest = json.loads((output_dir / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(
                ["FOLLOW_UP_CAUSE", None, "FOLLOW_UP_CAUSE"],
                [record["response_move"] for record in records],
            )
            self.assertEqual(
                ["FRESH_VERIFIED", "FRESH_REJECTED", "HEURISTIC_OBSERVED"],
                [record["response_move_provenance"] for record in records],
            )
            self.assertEqual(
                "observed_response_metadata_with_fresh_review_overlay_v1",
                manifest["response_move_policy"],
            )
            self.assertEqual(hashlib.sha256(ledger_path.read_bytes()).hexdigest(), manifest["response_move_review_ledger_sha256"])
            self.assertEqual(1, manifest["fresh_verified_response_move_count"])
            self.assertEqual(1, manifest["heuristically_observed_response_move_count"])
            self.assertEqual(1, manifest["rejected_response_move_review_count"])
            self.assertEqual(
                {"FRESH_REJECTED": 1, "FRESH_VERIFIED": 1, "HEURISTIC_OBSERVED": 1},
                manifest["response_move_provenance_counts"],
            )

    def test_fresh_verifier_ledger_rejects_a_digest_not_bound_to_the_exact_input(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path = root / "input.jsonl"
            ledger_path = root / "move-reviews.json"
            output_dir = root / "output"
            card = runtime_card(
                "FOLLOW_UP",
                context="아까 말한 일이 생각보다 오래 걸렸어",
                response="왜 그렇게 됐어?",
                example_id="human-style-000033",
            )
            input_path.write_text(json.dumps(card, ensure_ascii=False) + "\n", encoding="utf-8")
            ledger_path.write_text(
                json.dumps(
                    {
                        "schema": "nia-human-speech-style-response-move-review-ledger.v1",
                        "input_jsonl_sha256": "0" * 64,
                        "reviews": [fresh_review(card, "PASS", "FOLLOW_UP_CAUSE")],
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            source_coverage_policy = write_source_coverage_policy(input_path)

            result = subprocess.run(
                [
                    sys.executable,
                    str(Path(MATERIALIZER.__file__)),
                    "--input-jsonl",
                    str(input_path),
                    "--output-dir",
                    str(output_dir),
                    "--source-coverage-policy",
                    str(source_coverage_policy),
                    "--response-move-review-ledger",
                    str(ledger_path),
                ],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("not bound to this input", result.stderr)


def runtime_card(
    response_mode: str,
    context: str,
    response: str,
    example_id: str = "human-style-000001",
) -> dict[str, object]:
    return {
        "schema": "nia-human-speech-style-import-card.v1",
        "example_id": example_id,
        "response_mode": response_mode,
        "situation": "synthetic test only",
        "style_signals": ["synthetic"],
        "context_bubbles": [{"speaker": "가명1", "text": context}],
        "response_bubbles": [{"speaker": "가명2", "text": response}],
        "quality": "USER_RELEASED_REVIEW",
        "source_fingerprint": "sha256:" + "a" * 64,
        "consent_revision": "synthetic-test",
        "combined_chars": len(context) + len(response),
        "response_surface_has_card_local_alias": False,
        "embedding_model": "text-embedding-3-small",
    }


def fresh_review(
    card: dict[str, object],
    verdict: str,
    response_move: str | None,
    actual_response_action: str = "PASS",
) -> dict[str, object]:
    return {
        "schema": "nia-human-speech-style-response-move-review.v1",
        "example_id": card["example_id"],
        "source_fingerprint": card["source_fingerprint"],
        "response_mode": card["response_mode"],
        "verdict": verdict,
        "response_move": response_move,
        "reviewer_type": "FRESH_VERIFIER",
        "criteria": {
            "actual_response_action": actual_response_action,
            "response_move_fit": "PASS",
            "prompt_surface_safety": "PASS",
            "response_mode_fit": "PASS",
        },
    }


def write_source_coverage_policy(input_path: Path) -> Path:
    source_fingerprints = {
        json.loads(line)["source_fingerprint"]
        for line in input_path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    }
    policy = {
        "schema": "nia-human-speech-style-source-coverage.v1",
        "source_count": len(source_fingerprints),
        "source_fingerprint_set_sha256": hashlib.sha256(
            "\n".join(sorted(source_fingerprints)).encode(),
        ).hexdigest(),
    }
    policy_path = input_path.with_name("source-coverage-policy.json")
    policy_path.write_text(json.dumps(policy), encoding="utf-8")
    return policy_path


def classified(card: dict[str, object]) -> dict[str, object]:
    response_form = MATERIALIZER.classify_response_form(card)
    response_rhythm = MATERIALIZER.classify_response_rhythm(card)
    return {
        **card,
        "response_move": MATERIALIZER.classify_response_move(card),
        "response_form": response_form,
        "response_rhythm": response_rhythm,
        "provider_style_cues": MATERIALIZER.classify_provider_style_cues(
            card["response_mode"],
            response_form,
            response_rhythm,
        ),
    }


if __name__ == "__main__":
    unittest.main()
