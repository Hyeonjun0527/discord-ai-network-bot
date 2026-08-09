#!/usr/bin/env python3
"""private one-shot Speech-style RAG import artifact 준비기의 synthetic 회귀 테스트."""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from collections import Counter
from pathlib import Path


SCRIPT = Path(__file__).with_name("prepare-human-speech-style-rag-import.py")


class PrepareHumanSpeechStyleRagImportTest(unittest.TestCase):
    def test_prepares_protected_import_artifact_with_auditable_disabled_cards_preserved(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_candidate(
                root,
                complete_runtime_cards()
                + [runtime_card("human-style-000008", prompt_eligible=False, response_move=None, response_form=None)],
            )
            output = root / "import"

            result = run(candidate, output)

            self.assertEqual(0, result.returncode, result.stderr)
            cards = [
                json.loads(line)
                for line in (output / "human-speech-style-cards.jsonl").read_text(encoding="utf-8").splitlines()
            ]
            manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual("nia-human-speech-style-import-manifest.v10", manifest["schema"])
            self.assertEqual({"nia-human-speech-style-import-card.v4"}, {card["schema"] for card in cards})
            self.assertEqual("USER_RELEASED_REVIEW", manifest["quality"])
            self.assertEqual(8, manifest["record_count"])
            self.assertEqual(7, manifest["prompt_eligible_count"])
            self.assertEqual(1, manifest["prompt_disabled_count"])
            self.assertEqual(
                "closed_metadata_response_mode_then_semantic_tie_primary_style_cue_contrast_rerank_v11",
                manifest["retrieval_policy"],
            )
            self.assertEqual(
                "observed_response_metadata_with_fresh_review_overlay_v1",
                manifest["response_move_policy"],
            )
            self.assertEqual("closed_style_pattern_v1", manifest["prompt_surface_policy"])
            self.assertEqual({"AUDIT_ONLY": 1, "STYLE_PATTERN": 7}, manifest["prompt_surface_counts"])
            self.assertEqual(
                {
                    "ALIGNMENT_COMPLAINT_OR_LOW_ENERGY": 1,
                    "CARE_PHYSICAL_CONDITION": 1,
                    "COORDINATION_ACTION_PROPOSAL": 1,
                    "FOLLOW_UP_STATUS_OR_PROGRESS": 1,
                    "PLAY_BANTER": 1,
                    "REACTION_SURPRISE_OR_FUNNY": 2,
                    "SPECULATION_FUTURE": 1,
                },
                manifest["scene_trait_counts"],
            )
            self.assertEqual(
                {
                    "ALIGNMENT_LOW_KEY_ACK": 1,
                    "CARE_GENTLE_VALIDATE": 1,
                    "COORDINATION_PROPOSE": 1,
                    "FOLLOW_UP_DIRECT_CHECK": 1,
                    "PLAY_COUNTERTEASE": 1,
                    "REACTION_IMMEDIATE": 2,
                    "SPECULATION_LIGHT_HEDGE": 1,
                },
                manifest["provider_style_cue_counts"],
            )
            self.assertEqual(
                {"FRESH_VERIFIED": 1, "NONE": 7},
                manifest["response_move_provenance_counts"],
            )
            self.assertEqual(8, manifest["response_rhythm_coverage"])
            self.assertTrue(manifest["all_cards_user_released"])
            self.assertFalse(manifest["all_cards_formally_approved"])
            self.assertEqual("PASS", manifest["blind_quality_review"]["verdict"])
            self.assertEqual("PASS", manifest["rag_value_review"]["verdict"])
            self.assertEqual("PASS", manifest["retrieval_audit"]["verdict"])
            self.assertEqual([], manifest["retrieval_audit"]["reason_codes"])
            self.assertEqual("openai", manifest["retrieval_audit"]["embedding_provider"])
            self.assertEqual("text-embedding-3-small", manifest["retrieval_audit"]["embedding_model"])
            self.assertEqual("nia-human-speech-style-retrieval-audit.v4", manifest["retrieval_audit"]["schema"])
            self.assertEqual(
                "closed_metadata_response_mode_then_semantic_tie_primary_style_cue_contrast_rerank_v11",
                manifest["retrieval_audit"]["retrieval_policy"],
            )
            self.assertEqual(
                hashlib.sha256((output / "human-speech-style-cards.jsonl").read_bytes()).hexdigest(),
                manifest["blind_quality_review"]["artifact_jsonl_sha256"],
            )
            self.assertRegex(manifest["retrieval_audit_sha256"], r"^[0-9a-f]{64}$")
            self.assertEqual(
                manifest["retrieval_audit_sha256"],
                manifest["blind_quality_review"]["retrieval_audit_sha256"],
            )
            self.assertEqual(
                manifest["retrieval_audit_sha256"],
                manifest["rag_value_review"]["retrieval_audit_sha256"],
            )
            self.assertRegex(manifest["rag_value_review_sha256"], r"^[0-9a-f]{64}$")
            self.assertEqual(0o700, output.stat().st_mode & 0o777)
            self.assertEqual(0o600, (output / "human-speech-style-cards.jsonl").stat().st_mode & 0o777)
            self.assertEqual(0o600, (output / "manifest.json").stat().st_mode & 0o777)

    def test_allows_optional_time_coordination_metadata_without_a_secondary_contract_gate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_candidate(
                root,
                complete_runtime_cards(
                    response_mode="COORDINATION",
                    response_move="COORDINATION_TIME",
                    response_form="QUESTION",
                ),
            )

            result = run(candidate, root / "import")

            self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_a_candidate_without_searchable_cards(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_candidate(root, [runtime_card(prompt_eligible=False)])

            result = run(candidate, root / "import")

            self.assertNotEqual(0, result.returncode)
            self.assertIn("has no searchable cards", result.stderr)

    def test_rejects_a_candidate_that_does_not_cover_the_required_source_set(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_candidate(root, complete_runtime_cards())
            required_fingerprints = {
                "sha256:" + "a" * 64,
                "sha256:" + "b" * 64,
            }
            policy = {
                "schema": "nia-human-speech-style-source-coverage.v1",
                "source_count": len(required_fingerprints),
                "source_fingerprint_set_sha256": source_fingerprint_set_sha256(required_fingerprints),
            }
            policy_path = root / "required-source-coverage-policy.json"
            policy_path.write_text(json.dumps(policy), encoding="utf-8")
            manifest_path = candidate / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["expected_source_count"] = policy["source_count"]
            manifest["expected_source_fingerprint_set_sha256"] = policy["source_fingerprint_set_sha256"]
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            result = run(candidate, root / "import", source_coverage_policy=policy_path)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("does not match required source coverage set", result.stderr)

    def test_rejects_a_candidate_that_lacks_a_searchable_response_mode(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_candidate(root, [runtime_card()])

            result = run(candidate, root / "import")

            self.assertNotEqual(0, result.returncode)
            self.assertIn("does not cover every response mode", result.stderr)

    def test_rejects_legacy_v25_card_or_candidate_manifest_schemas(self) -> None:
        cases = (
            ("card", "nia-human-speech-style-import-card.v3", "candidate card schema is invalid"),
            ("manifest", "nia-human-speech-style-runtime-candidate-manifest.v6", "manifest schema is unsupported"),
        )
        for target, legacy_schema, message in cases:
            with self.subTest(target=target), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                candidate = write_candidate(root, complete_runtime_cards())
                if target == "card":
                    cards_path = candidate / "human-speech-style-cards.jsonl"
                    cards = [json.loads(line) for line in cards_path.read_text(encoding="utf-8").splitlines()]
                    cards[0]["schema"] = legacy_schema
                    cards_path.write_text("\n".join(json.dumps(card) for card in cards) + "\n", encoding="utf-8")
                    manifest_path = candidate / "manifest.json"
                    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                    manifest["jsonl_sha256"] = hashlib.sha256(cards_path.read_bytes()).hexdigest()
                    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                else:
                    manifest_path = candidate / "manifest.json"
                    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                    manifest["schema"] = legacy_schema
                    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

                result = run(candidate, root / "import")

                self.assertNotEqual(0, result.returncode)
                self.assertIn(message, result.stderr)

    def test_rejects_candidate_manifest_with_unapproved_fields(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_candidate(root, complete_runtime_cards())
            manifest_path = candidate / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["unexpected_metadata"] = "synthetic"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            result = run(candidate, root / "import")

            self.assertNotEqual(0, result.returncode)
            self.assertIn("manifest fields are invalid", result.stderr)

    def test_rejects_malformed_response_rhythm_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            malformed = runtime_card()
            malformed["response_rhythm"] = ["SHORT_REACTION", "SHORT_REACTION"]
            candidate = write_candidate(root, [malformed])

            result = run(candidate, root / "import")

            self.assertNotEqual(0, result.returncode)
            self.assertIn("response rhythm metadata is invalid", result.stderr)

    def test_rejects_unknown_or_mode_incompatible_closed_response_metadata(self) -> None:
        cases = (
            ("response_move", "UNKNOWN_MOVE", "response move enum is invalid"),
            ("response_move", "CARE_PHYSICAL", "response move does not match response mode"),
            ("response_form", "SUPPORTIVE", "response form does not match response mode"),
            ("response_rhythm", ["GENTLE_CARE", "SINGLE_BUBBLE"], "response rhythm cue does not match response mode"),
        )
        for field, value, message in cases:
            with self.subTest(field=field, value=value), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                cards = complete_runtime_cards()
                cards[0][field] = value
                candidate = write_candidate(root, cards)

                result = run(candidate, root / "import")

                self.assertNotEqual(0, result.returncode)
                self.assertIn(message, result.stderr)

    def test_rejects_unknown_excessive_or_mode_incompatible_scene_traits(self) -> None:
        cases = (
            (["UNKNOWN_TRAIT"], "scene trait enum is invalid"),
            (["CARE_PHYSICAL_CONDITION"], "scene trait does not match response mode"),
            (
                ["REACTION_GOOD_NEWS", "REACTION_SURPRISE_OR_FUNNY", "REACTION_GOOD_NEWS"],
                "scene traits are invalid",
            ),
        )
        for scene_traits, message in cases:
            with self.subTest(scene_traits=scene_traits), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                cards = complete_runtime_cards()
                cards[0]["scene_traits"] = scene_traits
                candidate = write_candidate(root, cards)

                result = run(candidate, root / "import")

                self.assertNotEqual(0, result.returncode)
                self.assertIn(message, result.stderr)

    def test_rejects_unknown_multiple_or_mode_incompatible_primary_provider_style_cues(self) -> None:
        cases = (
            (["UNKNOWN_PROVIDER_STYLE_CUE"], "provider style cue enum is invalid"),
            (["CARE_GENTLE_VALIDATE"], "provider style cue does not match response mode"),
            (
                ["REACTION_IMMEDIATE", "REACTION_LAUGH_ALONG"],
                "provider style cues are invalid",
            ),
            (["REACTION_IMMEDIATE", "REACTION_IMMEDIATE"], "provider style cues are invalid"),
        )
        for provider_style_cues, message in cases:
            with self.subTest(provider_style_cues=provider_style_cues), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                cards = complete_runtime_cards()
                cards[0]["provider_style_cues"] = provider_style_cues
                candidate = write_candidate(root, cards)

                result = run(candidate, root / "import")

                self.assertNotEqual(0, result.returncode)
                self.assertIn(message, result.stderr)

    def test_rejects_provider_style_cues_not_derived_from_observed_response_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cards = complete_runtime_cards()
            cards[0]["provider_style_cues"] = ["REACTION_LAUGH_ALONG"]
            candidate = write_candidate(root, cards)

            result = run(candidate, root / "import")

            self.assertNotEqual(0, result.returncode)
            self.assertIn("provider style cues do not match observed response metadata", result.stderr)

    def test_rejects_non_primary_provider_style_cue_when_higher_priority_cue_is_observed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cards = complete_runtime_cards()
            cards[0]["response_rhythm"] = [
                "POSITIVE_ACKNOWLEDGMENT",
                "SHORT_REACTION",
                "SINGLE_BUBBLE",
            ]
            cards[0]["provider_style_cues"] = ["REACTION_IMMEDIATE"]
            candidate = write_candidate(root, cards)

            result = run(candidate, root / "import")

            self.assertNotEqual(0, result.returncode)
            self.assertIn("provider style cues do not match observed response metadata", result.stderr)

    def test_rejects_invalid_or_mismatched_response_move_provenance(self) -> None:
        cases = (
            ("UNKNOWN_PROVENANCE", "response-move provenance is invalid"),
            ("NONE", "response-move provenance does not match response move"),
            ("FRESH_REJECTED", "response-move provenance does not match response move"),
        )
        for provenance, message in cases:
            with self.subTest(provenance=provenance), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                cards = complete_runtime_cards()
                cards[0]["response_move_provenance"] = provenance
                candidate = write_candidate(root, cards)

                result = run(candidate, root / "import")

                self.assertNotEqual(0, result.returncode)
                self.assertIn(message, result.stderr)

    def test_rejects_manifest_scene_trait_provider_style_cue_or_provenance_count_mismatch(self) -> None:
        for field, value, message in (
            ("scene_trait_counts", {}, "scene-trait count mismatch"),
            ("provider_style_cue_counts", {}, "provider-style-cue count mismatch"),
            ("response_move_provenance_counts", {"FRESH_VERIFIED": 7}, "response-move provenance count mismatch"),
        ):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                candidate = write_candidate(root, complete_runtime_cards())
                manifest_path = candidate / "manifest.json"
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                manifest[field] = value
                manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

                result = run(candidate, root / "import")

                self.assertNotEqual(0, result.returncode)
                self.assertIn(message, result.stderr)

    def test_requires_fresh_evidence_but_allows_explicit_heuristic_observations(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cards = complete_runtime_cards()
            cards[0]["response_move_provenance"] = "HEURISTIC_OBSERVED"
            no_fresh_candidate = write_candidate(root, cards)

            no_fresh_result = run(no_fresh_candidate, root / "no-fresh-import")

            self.assertNotEqual(0, no_fresh_result.returncode)
            self.assertIn("has no fresh verified response-move evidence", no_fresh_result.stderr)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cards = complete_runtime_cards()
            cards[5]["response_move"] = "CARE_PHYSICAL"
            cards[5]["response_move_provenance"] = "HEURISTIC_OBSERVED"
            heuristic_candidate = write_candidate(root, cards)

            heuristic_result = run(heuristic_candidate, root / "heuristic-import")

            self.assertEqual(0, heuristic_result.returncode, heuristic_result.stderr)

    def test_rejects_a_candidate_without_the_honest_response_move_policy(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_candidate(root, complete_runtime_cards())
            manifest_path = candidate / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["response_move_policy"] = "fresh_verifier_rejection_overlay_on_observed_response_metadata_v1"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            result = run(candidate, root / "import")

            self.assertNotEqual(0, result.returncode)
            self.assertIn("response-move policy is invalid", result.stderr)

    def test_allows_style_pattern_card_with_card_local_alias_safety_flag(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cards = complete_runtime_cards()
            alias_bearing = cards[0]
            alias_bearing["response_surface_has_card_local_alias"] = True
            candidate = write_candidate(root, [alias_bearing, *cards[1:]])

            result = run(candidate, root / "import")

            self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_legacy_context_response_pair_even_with_card_local_alias_safety_flag(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            alias_bearing = complete_runtime_cards()[0]
            alias_bearing["prompt_surface"] = "CONTEXT_RESPONSE_PAIR"
            alias_bearing["response_surface_has_card_local_alias"] = True
            candidate = write_candidate(root, [alias_bearing, *complete_runtime_cards()[1:]])

            result = run(candidate, root / "import")

            self.assertNotEqual(0, result.returncode)
            self.assertIn("prompt surface is invalid", result.stderr)

    def test_requires_a_passing_blind_quality_review_bound_to_the_candidate_digest(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_candidate(root, complete_runtime_cards())
            audit = write_retrieval_audit(candidate)
            failed_review = write_blind_quality_review(candidate, audit, {"verdict": "FAIL"})

            result = run(candidate, root / "import", failed_review, audit)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("does not pass quality gates", result.stderr)

    def test_rejects_a_blind_quality_review_for_a_different_candidate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_candidate(root, complete_runtime_cards())
            audit = write_retrieval_audit(candidate)
            mismatched_review = write_blind_quality_review(candidate, audit, {"artifact_jsonl_sha256": "0" * 64})

            result = run(candidate, root / "import", mismatched_review, audit)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("artifact digest does not match", result.stderr)

    def test_rejects_a_blind_quality_review_that_contains_unapproved_fields(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_candidate(root, complete_runtime_cards())
            audit = write_retrieval_audit(candidate)
            review = write_blind_quality_review(candidate, audit, {"raw_context": "synthetic forbidden field"})

            result = run(candidate, root / "import", review, audit)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("review fields are invalid", result.stderr)

    def test_rejects_an_actual_retrieval_audit_for_a_different_candidate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_candidate(root, complete_runtime_cards())
            audit = write_retrieval_audit(candidate, {"candidate_jsonl_sha256": "0" * 64})

            result = run(candidate, root / "import", retrieval_audit=audit)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("retrieval audit artifact digest does not match", result.stderr)

    def test_rejects_a_failed_actual_retrieval_audit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_candidate(root, complete_runtime_cards())
            audit = write_retrieval_audit(candidate, {"verdict": "FAIL"})

            result = run(candidate, root / "import", retrieval_audit=audit)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("retrieval audit does not pass quality gates", result.stderr)

    def test_rejects_an_actual_retrieval_audit_with_unresolved_findings(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_candidate(root, complete_runtime_cards())
            audit = write_retrieval_audit(candidate, {"reason_codes": ["synthetic-finding"]})

            result = run(candidate, root / "import", retrieval_audit=audit)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("retrieval audit has unresolved findings", result.stderr)

    def test_rejects_an_actual_retrieval_audit_with_invalid_run_metadata(self) -> None:
        for field, value, message in (
            ("embedding_provider", "synthetic", "retrieval audit embedding provider is invalid"),
            ("embedding_model", "synthetic", "retrieval audit embedding model is invalid"),
            ("execution_scope", "synthetic", "retrieval audit execution scope is invalid"),
        ):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                candidate = write_candidate(root, complete_runtime_cards())
                audit = write_retrieval_audit(candidate, {field: value})

                result = run(candidate, root / "import", retrieval_audit=audit)

                self.assertNotEqual(0, result.returncode)
                self.assertIn(message, result.stderr)

    def test_rejects_an_actual_retrieval_audit_without_the_exact_closed_metadata_policy(self) -> None:
        cases = (
            ("legacy", "response_mode_then_semantic_tie_primary_style_cue_contrast_rerank_v10", "retrieval audit retrieval policy is invalid"),
            ("missing", None, "retrieval audit fields are invalid"),
        )
        for mutation, value, message in cases:
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                candidate = write_candidate(root, complete_runtime_cards())
                audit = write_retrieval_audit(candidate)
                payload = json.loads(audit.read_text(encoding="utf-8"))
                if value is None:
                    del payload["retrieval_policy"]
                else:
                    payload["retrieval_policy"] = value
                audit.write_text(json.dumps(payload), encoding="utf-8")

                result = run(candidate, root / "import", retrieval_audit=audit)

                self.assertNotEqual(0, result.returncode)
                self.assertIn(message, result.stderr)

    def test_rejects_an_actual_retrieval_audit_below_holdout_coverage(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_candidate(root, complete_runtime_cards())
            audit = write_retrieval_audit(candidate)
            payload = json.loads(audit.read_text(encoding="utf-8"))
            payload["independent_holdout"]["returned_reference_case_count"] = 27
            payload["independent_holdout"]["exact_mode_return_case_count"] = 27
            audit.write_text(json.dumps(payload), encoding="utf-8")

            result = run(candidate, root / "import", retrieval_audit=audit)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("retrieval audit independent holdout coverage is insufficient", result.stderr)

    def test_rejects_an_actual_retrieval_audit_without_source_diverse_top2_references(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_candidate(root, complete_runtime_cards())
            audit = write_retrieval_audit(candidate)
            payload = json.loads(audit.read_text(encoding="utf-8"))
            payload["independent_holdout"]["source_diverse_top2_case_count"] = 0
            audit.write_text(json.dumps(payload), encoding="utf-8")

            result = run(candidate, root / "import", retrieval_audit=audit)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("retrieval audit independent holdout source diversity is insufficient", result.stderr)

    def test_rejects_an_actual_retrieval_audit_with_post_query_empty_results(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_candidate(root, complete_runtime_cards())
            audit = write_retrieval_audit(candidate)
            payload = json.loads(audit.read_text(encoding="utf-8"))
            payload["fixed_probe"]["unexpected_post_query_empty_count"] = 1
            audit.write_text(json.dumps(payload), encoding="utf-8")

            result = run(candidate, root / "import", retrieval_audit=audit)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("retrieval audit fixed probe has post-query empty results", result.stderr)

    def test_rejects_a_blind_quality_review_not_bound_to_the_actual_retrieval_audit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_candidate(root, complete_runtime_cards())
            audit = write_retrieval_audit(candidate)
            review = write_blind_quality_review(candidate, audit, {"retrieval_audit_sha256": "0" * 64})

            result = run(candidate, root / "import", review, audit)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("not bound to the retrieval audit", result.stderr)

    def test_requires_a_fresh_comparative_rag_value_review_bound_to_the_actual_retrieval_audit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_candidate(root, complete_runtime_cards())
            audit = write_retrieval_audit(candidate)
            failed_review = write_rag_value_review(candidate, audit, {"verdict": "FAIL"})

            result = run(candidate, root / "import", retrieval_audit=audit, rag_value_review=failed_review)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("RAG value review does not pass quality gates", result.stderr)

    def test_rejects_a_rag_value_review_for_a_different_candidate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_candidate(root, complete_runtime_cards())
            audit = write_retrieval_audit(candidate)
            mismatched_review = write_rag_value_review(candidate, audit, {"artifact_jsonl_sha256": "0" * 64})

            result = run(candidate, root / "import", retrieval_audit=audit, rag_value_review=mismatched_review)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("RAG value review artifact digest does not match", result.stderr)

    def test_rejects_a_rag_value_review_with_an_unresolved_comparative_finding(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_candidate(root, complete_runtime_cards())
            audit = write_retrieval_audit(candidate)
            unsafe_review = write_rag_value_review(candidate, audit, {"worse_than_mode_baseline_count": 1})

            result = run(candidate, root / "import", retrieval_audit=audit, rag_value_review=unsafe_review)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("RAG value review has unresolved findings", result.stderr)

    def test_requires_the_private_quality_evidence_arguments(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_candidate(root, complete_runtime_cards())

            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--candidate-dir", str(candidate), "--output-dir", str(root / "import")],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("--blind-quality-review", result.stderr)
            self.assertIn("--retrieval-audit", result.stderr)
            self.assertIn("--rag-value-review", result.stderr)


def run(
    candidate: Path,
    output: Path,
    blind_quality_review: Path | None = None,
    retrieval_audit: Path | None = None,
    rag_value_review: Path | None = None,
    source_coverage_policy: Path | None = None,
) -> subprocess.CompletedProcess[str]:
    audit = retrieval_audit or write_retrieval_audit(candidate)
    review = blind_quality_review or write_blind_quality_review(candidate, audit)
    value_review = rag_value_review or write_rag_value_review(candidate, audit)
    return subprocess.run(
        [
            sys.executable,
            str(SCRIPT),
            "--candidate-dir",
            str(candidate),
            "--output-dir",
            str(output),
            "--source-coverage-policy",
            str(source_coverage_policy or candidate / "source-coverage-policy.json"),
            "--blind-quality-review",
            str(review),
            "--retrieval-audit",
            str(audit),
            "--rag-value-review",
            str(value_review),
        ],
        check=False,
        capture_output=True,
        text=True,
    )


def write_candidate(root: Path, rows: list[dict[str, object]]) -> Path:
    candidate = root / "candidate"
    candidate.mkdir()
    jsonl = candidate / "human-speech-style-cards.jsonl"
    jsonl.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")
    digest = hashlib.sha256(jsonl.read_bytes()).hexdigest()
    source_fingerprints = {row["source_fingerprint"] for row in rows}
    source_coverage_policy = {
        "schema": "nia-human-speech-style-source-coverage.v1",
        "source_count": len(source_fingerprints),
        "source_fingerprint_set_sha256": source_fingerprint_set_sha256(source_fingerprints),
    }
    (candidate / "source-coverage-policy.json").write_text(json.dumps(source_coverage_policy), encoding="utf-8")
    scene_trait_counts = dict(sorted(Counter(
        trait
        for row in rows
        for trait in row["scene_traits"]
    ).items()))
    provider_style_cue_counts = dict(sorted(Counter(
        cue
        for row in rows
        for cue in row["provider_style_cues"]
    ).items()))
    response_move_provenance_counts = dict(sorted(Counter(
        row["response_move_provenance"]
        for row in rows
    ).items()))
    prompt_eligible_rows = [row for row in rows if row["prompt_eligible"] is True]
    response_move_metadata_counts = dict(sorted(Counter(
        row["response_move"]
        for row in rows
        if row["response_move"] is not None
    ).items()))
    prompt_eligible_response_move_counts = dict(sorted(Counter(
        row["response_move"]
        for row in prompt_eligible_rows
        if row["response_move"] is not None
    ).items()))
    prompt_eligible_response_move_source_counts = {
        response_move: len({
            row["source_fingerprint"]
            for row in prompt_eligible_rows
            if row["response_move"] == response_move
        })
        for response_move in prompt_eligible_response_move_counts
    }
    response_form_metadata_counts = dict(sorted(Counter(
        row["response_form"]
        for row in rows
        if row["response_form"] is not None
    ).items()))
    response_rhythm_cue_counts = dict(sorted(Counter(
        cue
        for row in rows
        for cue in row["response_rhythm"]
    ).items()))
    response_rhythm_behavior_coverage = sum(
        any(cue not in DELIVERY_ONLY_RHYTHM_CUES for cue in row["response_rhythm"])
        for row in rows
    )
    manifest = {
        "schema": "nia-human-speech-style-runtime-candidate-manifest.v8",
        "input_jsonl_sha256": "b" * 64,
        "record_count": len(rows),
        "jsonl_sha256": digest,
        "prompt_eligible_count": len(prompt_eligible_rows),
        "prompt_disabled_count": len(rows) - len(prompt_eligible_rows),
        "prompt_eligible_by_response_mode": {
            mode: sum(row["response_mode"] == mode for row in prompt_eligible_rows)
            for mode in RESPONSE_MODES
            if any(row["response_mode"] == mode for row in prompt_eligible_rows)
        },
        "prompt_ineligible_reason_counts": {},
        "response_move_metadata_counts": response_move_metadata_counts,
        "prompt_eligible_response_move_counts": prompt_eligible_response_move_counts,
        "prompt_eligible_response_move_source_counts": prompt_eligible_response_move_source_counts,
        "response_form_metadata_counts": response_form_metadata_counts,
        "response_rhythm_cue_counts": response_rhythm_cue_counts,
        "response_rhythm_coverage": len(rows),
        "response_rhythm_behavior_coverage": response_rhythm_behavior_coverage,
        "response_rhythm_delivery_only_count": len(rows) - response_rhythm_behavior_coverage,
        "retrieval_policy": "closed_metadata_response_mode_then_semantic_tie_primary_style_cue_contrast_rerank_v11",
        "response_move_policy": "observed_response_metadata_with_fresh_review_overlay_v1",
        "response_move_review_ledger_sha256": "a" * 64,
        "fresh_verified_response_move_count": response_move_provenance_counts.get("FRESH_VERIFIED", 0),
        "heuristically_observed_response_move_count": response_move_provenance_counts.get("HEURISTIC_OBSERVED", 0),
        "rejected_response_move_review_count": response_move_provenance_counts.get("FRESH_REJECTED", 0),
        "prompt_surface_policy": "closed_style_pattern_v1",
        "scene_trait_counts": scene_trait_counts,
        "provider_style_cue_counts": provider_style_cue_counts,
        "response_move_provenance_counts": response_move_provenance_counts,
        "source_fingerprint_count": len(source_fingerprints),
        "expected_source_count": source_coverage_policy["source_count"],
        "expected_source_fingerprint_set_sha256": source_coverage_policy["source_fingerprint_set_sha256"],
        "source_coverage_complete": True,
        "quality_counts": dict(sorted(Counter(row["quality"] for row in rows).items())),
        "purpose": "synthetic private test candidate",
    }
    (candidate / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    return candidate


def source_fingerprint_set_sha256(source_fingerprints: set[str]) -> str:
    return hashlib.sha256("\n".join(sorted(source_fingerprints)).encode()).hexdigest()


def complete_runtime_cards(
    response_mode: str | None = None,
    response_move: str | None = None,
    response_form: str | None = None,
) -> list[dict[str, object]]:
    cards = [
        runtime_card(
            example_id=f"human-style-{index:06}",
            response_mode=mode,
            response_move="REACTION_SURPRISE" if mode == "REACTION" else None,
            response_form=DEFAULT_RESPONSE_FORM_BY_MODE[mode],
            response_rhythm=DEFAULT_RESPONSE_RHYTHM_BY_MODE[mode],
            scene_traits=DEFAULT_SCENE_TRAITS_BY_MODE[mode],
        )
        for index, mode in enumerate(RESPONSE_MODES, start=1)
    ]
    if response_mode is not None:
        index = RESPONSE_MODES.index(response_mode)
        cards[index] = runtime_card(
            example_id=f"human-style-{index + 1:06}",
            response_mode=response_mode,
            response_move=response_move,
            response_form=response_form or DEFAULT_RESPONSE_FORM_BY_MODE[response_mode],
            response_rhythm=DEFAULT_RESPONSE_RHYTHM_BY_MODE[response_mode],
            scene_traits=DEFAULT_SCENE_TRAITS_BY_MODE[response_mode],
        )
    return cards


def fixture_provider_style_cues(
    response_mode: str,
    response_form: str | None,
    response_rhythm: list[str],
) -> list[str]:
    cues = [
        cue
        for cue, compatible_forms, compatible_rhythm in FIXTURE_PROVIDER_STYLE_CUE_RULES_BY_MODE[response_mode]
        if response_form in compatible_forms or compatible_rhythm.intersection(response_rhythm)
    ]
    return cues[:1]


def runtime_card(
    example_id: str = "human-style-000001",
    prompt_eligible: bool = True,
    prompt_surface: str | None = None,
    response_mode: str = "REACTION",
    response_move: str | None = "REACTION_SURPRISE",
    response_form: str | None = "EXPRESSIVE",
    response_rhythm: list[str] | None = None,
    scene_traits: list[str] | None = None,
    provider_style_cues: list[str] | None = None,
    response_move_provenance: str | None = None,
) -> dict[str, object]:
    resolved_prompt_surface = prompt_surface or ("STYLE_PATTERN" if prompt_eligible else "AUDIT_ONLY")
    resolved_response_rhythm = response_rhythm if response_rhythm is not None else ["SHORT_REACTION", "SINGLE_BUBBLE"]
    resolved_scene_traits = scene_traits if scene_traits is not None else ["REACTION_SURPRISE_OR_FUNNY"]
    resolved_provider_style_cues = (
        provider_style_cues
        if provider_style_cues is not None
        else fixture_provider_style_cues(response_mode, response_form, resolved_response_rhythm)
    )
    resolved_provenance = response_move_provenance if response_move_provenance is not None else (
        "FRESH_VERIFIED" if response_move is not None else "NONE"
    )
    return {
        "schema": "nia-human-speech-style-import-card.v4",
        "example_id": example_id,
        "response_mode": response_mode,
        "situation": "synthetic test only",
        "style_signals": ["synthetic"],
        "context_bubbles": [{"speaker": "가명1", "text": "synthetic context"}],
        "response_bubbles": [{"speaker": "가명2", "text": "synthetic response"}],
        "quality": "USER_RELEASED_REVIEW",
        "source_fingerprint": "sha256:" + "a" * 64,
        "consent_revision": "synthetic-test",
        "combined_chars": 40,
        "prompt_eligible": prompt_eligible,
        "prompt_surface": resolved_prompt_surface,
        "response_surface_has_card_local_alias": False,
        "response_move": response_move,
        "response_move_provenance": resolved_provenance,
        "response_form": response_form,
        "response_rhythm": resolved_response_rhythm,
        "scene_traits": resolved_scene_traits,
        "provider_style_cues": resolved_provider_style_cues,
        "embedding_model": "text-embedding-3-small",
    }


def write_retrieval_audit(candidate: Path, overrides: dict[str, object] | None = None) -> Path:
    digest = hashlib.sha256((candidate / "human-speech-style-cards.jsonl").read_bytes()).hexdigest()
    fixed_probe = {
        "case_count": 35,
        "exact_mode_return_case_count": 35,
        "policy_abstention_count": 0,
        "returned_reference_card_count": 70,
        "returned_reference_case_count": 35,
        "unexpected_post_query_empty_count": 0,
    }
    independent_holdout = {
        **fixed_probe,
        "source_diverse_top2_case_count": 35,
    }
    payload: dict[str, object] = {
        "schema": "nia-human-speech-style-retrieval-audit.v4",
        "candidate_jsonl_sha256": digest,
        "retrieval_policy": "closed_metadata_response_mode_then_semantic_tie_primary_style_cue_contrast_rerank_v11",
        "embedding_provider": "openai",
        "embedding_model": "text-embedding-3-small",
        "execution_scope": "ephemeral_h2_only_no_judge_no_discord_no_provider_generation",
        "fixed_probe": fixed_probe,
        "independent_holdout": independent_holdout,
        "verdict": "PASS",
        "reason_codes": [],
    }
    if overrides:
        payload.update(overrides)
    audit = candidate / "retrieval-audit.json"
    audit.write_text(json.dumps(payload), encoding="utf-8")
    return audit


def write_blind_quality_review(
    candidate: Path,
    retrieval_audit: Path,
    overrides: dict[str, object] | None = None,
) -> Path:
    digest = hashlib.sha256((candidate / "human-speech-style-cards.jsonl").read_bytes()).hexdigest()
    case_counts = {mode: 10 for mode in RESPONSE_MODES}
    top1_counts = {mode: 7 for mode in RESPONSE_MODES}
    top1_counts["COORDINATION"] = 6
    top2_counts = {mode: 8 for mode in RESPONSE_MODES}
    payload: dict[str, object] = {
        "schema": "nia-human-speech-style-blind-quality-review.v2",
        "artifact_jsonl_sha256": digest,
        "verdict": "PASS",
        "case_count": sum(case_counts.values()),
        "case_count_by_response_mode": case_counts,
        "top1_useful_count": sum(top1_counts.values()),
        "top2_any_useful_count": sum(top2_counts.values()),
        "top1_useful_by_response_mode": top1_counts,
        "top2_any_useful_by_response_mode": top2_counts,
        "unsafe_provider_surface_count": 0,
        "private_context_dependency_count": 0,
        "copy_risk_count": 0,
        "retrieval_audit_sha256": hashlib.sha256(retrieval_audit.read_bytes()).hexdigest(),
    }
    if overrides:
        payload.update(overrides)
    review = candidate / "blind-quality-review.json"
    review.write_text(json.dumps(payload), encoding="utf-8")
    return review


def write_rag_value_review(
    candidate: Path,
    retrieval_audit: Path,
    overrides: dict[str, object] | None = None,
) -> Path:
    digest = hashlib.sha256((candidate / "human-speech-style-cards.jsonl").read_bytes()).hexdigest()
    case_counts = {mode: 10 for mode in RESPONSE_MODES}
    top1_counts = {mode: 7 for mode in RESPONSE_MODES}
    top2_counts = {mode: 8 for mode in RESPONSE_MODES}
    payload: dict[str, object] = {
        "schema": "nia-human-speech-style-rag-value-review.v1",
        "artifact_jsonl_sha256": digest,
        "retrieval_audit_sha256": hashlib.sha256(retrieval_audit.read_bytes()).hexdigest(),
        "review_protocol": "fixed_mode_baseline_v1",
        "reviewer_type": "FRESH_COMPARATIVE_VERIFIER",
        "verdict": "PASS",
        "case_count": sum(case_counts.values()),
        "case_count_by_response_mode": case_counts,
        "top1_value_add_count": sum(top1_counts.values()),
        "top2_any_value_add_count": sum(top2_counts.values()),
        "top1_value_add_by_response_mode": top1_counts,
        "top2_any_value_add_by_response_mode": top2_counts,
        "worse_than_mode_baseline_count": 0,
        "unsupported_specificity_count": 0,
        "unsafe_provider_surface_count": 0,
    }
    if overrides:
        payload.update(overrides)
    review = candidate / "rag-value-review.json"
    review.write_text(json.dumps(payload), encoding="utf-8")
    return review


RESPONSE_MODES = (
    "REACTION",
    "ALIGNMENT",
    "PLAY",
    "FOLLOW_UP",
    "SPECULATION",
    "CARE",
    "COORDINATION",
)
DELIVERY_ONLY_RHYTHM_CUES = {
    "TINY_REPLY",
    "SHORT_REPLY",
    "MEDIUM_REPLY",
    "LONGER_REPLY",
    "TRAILING_PAUSE",
    "CASUAL_SHORT_FORM",
    "SOFT_EMOTION_MARKER",
    "SINGLE_BUBBLE",
    "MULTI_BUBBLE",
}
DEFAULT_RESPONSE_FORM_BY_MODE = {
    "REACTION": "EXPRESSIVE",
    "ALIGNMENT": "ALIGN_AND_ADD",
    "PLAY": "PLAYFUL_RETURN",
    "FOLLOW_UP": "QUESTION",
    "SPECULATION": "HEDGED_GUESS",
    "CARE": "SUPPORTIVE",
    "COORDINATION": "PROPOSAL",
}
DEFAULT_RESPONSE_RHYTHM_BY_MODE = {
    "REACTION": ["SHORT_REACTION", "SINGLE_BUBBLE"],
    "ALIGNMENT": ["AGREE_AND_ADD", "SINGLE_BUBBLE"],
    "PLAY": ["PLAYFUL_RETURN", "SINGLE_BUBBLE"],
    "FOLLOW_UP": ["DIRECT_QUESTION", "SINGLE_BUBBLE"],
    "SPECULATION": ["HEDGED_GUESS", "SINGLE_BUBBLE"],
    "CARE": ["GENTLE_CARE", "SINGLE_BUBBLE"],
    "COORDINATION": ["ACTION_PROPOSAL", "SINGLE_BUBBLE"],
}
DEFAULT_SCENE_TRAITS_BY_MODE = {
    "REACTION": ["REACTION_SURPRISE_OR_FUNNY"],
    "ALIGNMENT": ["ALIGNMENT_COMPLAINT_OR_LOW_ENERGY"],
    "PLAY": ["PLAY_BANTER"],
    "FOLLOW_UP": ["FOLLOW_UP_STATUS_OR_PROGRESS"],
    "SPECULATION": ["SPECULATION_FUTURE"],
    "CARE": ["CARE_PHYSICAL_CONDITION"],
    "COORDINATION": ["COORDINATION_ACTION_PROPOSAL"],
}
FIXTURE_PROVIDER_STYLE_CUE_RULES_BY_MODE = {
    "REACTION": (
        ("REACTION_WARM_ACK", set(), {"POSITIVE_ACKNOWLEDGMENT"}),
        ("REACTION_LAUGH_ALONG", set(), {"LAUGHTER"}),
        ("REACTION_IMMEDIATE", {"EXPRESSIVE"}, {"SHORT_REACTION"}),
    ),
    "ALIGNMENT": (
        ("ALIGNMENT_SHARED_FEELING", set(), {"SHARED_FEELING"}),
        ("ALIGNMENT_LOW_KEY_ACK", {"ALIGN_AND_ADD"}, {"AGREE_AND_ADD"}),
    ),
    "PLAY": (
        ("PLAY_LIGHT_EXAGGERATION", set(), {"LIGHT_EXAGGERATION"}),
        ("PLAY_COUNTERTEASE", {"PLAYFUL_RETURN"}, {"PLAYFUL_RETURN"}),
    ),
    "FOLLOW_UP": (
        ("FOLLOW_UP_DIRECT_CHECK", set(), {"DIRECT_QUESTION"}),
        ("FOLLOW_UP_SOFT_CHECK", {"QUESTION"}, set()),
    ),
    "SPECULATION": (
        ("SPECULATION_LIGHT_HEDGE", {"HEDGED_GUESS"}, {"HEDGED_GUESS"}),
    ),
    "CARE": (
        ("CARE_SOFT_NUDGE", set(), {"SUPPORTIVE_NUDGE"}),
        ("CARE_GENTLE_VALIDATE", {"SUPPORTIVE"}, {"GENTLE_CARE"}),
    ),
    "COORDINATION": (
        ("COORDINATION_CONFIRM", set(), {"COORDINATION_CHECK"}),
        ("COORDINATION_ASK_ONE", {"QUESTION"}, set()),
        ("COORDINATION_PROPOSE", {"PROPOSAL"}, {"ACTION_PROPOSAL"}),
    ),
}


if __name__ == "__main__":
    unittest.main()
