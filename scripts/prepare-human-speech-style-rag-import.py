#!/usr/bin/env python3
"""검증된 private runtime candidate를 one-shot 운영 import artifact로 봉인한다.

모든 카드가 encrypted audit record로 보존된다. 그중 `prompt_eligible=true`인 카드만 같은 7개 response enum 안의
Speech 말투 검색 후보가 된다. runtime renderer는 원문 대화·실제 답변 대신 닫힌 response mode/form/rhythm metadata로
만든 비식별 style pattern만 보인다. 이 스크립트는 카드 문구를 출력하지 않고 digest·건수·승인 경로만 manifest에 기록한다.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
from collections import Counter
from pathlib import Path
from typing import Any


CANDIDATE_MANIFEST_SCHEMA = "nia-human-speech-style-runtime-candidate-manifest.v8"
IMPORT_MANIFEST_SCHEMA = "nia-human-speech-style-import-manifest.v10"
IMPORT_CARD_SCHEMA = "nia-human-speech-style-import-card.v4"
BLIND_QUALITY_REVIEW_SCHEMA = "nia-human-speech-style-blind-quality-review.v2"
RETRIEVAL_AUDIT_SCHEMA = "nia-human-speech-style-retrieval-audit.v4"
RAG_VALUE_REVIEW_SCHEMA = "nia-human-speech-style-rag-value-review.v1"
RAG_VALUE_REVIEW_PROTOCOL = "fixed_mode_baseline_v1"
RAG_VALUE_REVIEWER_TYPE = "FRESH_COMPARATIVE_VERIFIER"
FORMAL_QUALITY = "CURATION_APPROVED"
USER_RELEASED_QUALITY = "USER_RELEASED_REVIEW"
ALLOWED_QUALITIES = {FORMAL_QUALITY, USER_RELEASED_QUALITY}
RETRIEVAL_POLICY = "closed_metadata_response_mode_then_semantic_tie_primary_style_cue_contrast_rerank_v11"
RESPONSE_MOVE_POLICY = "observed_response_metadata_with_fresh_review_overlay_v1"
PROMPT_SURFACE_POLICY = "closed_style_pattern_v1"
PROMPT_SURFACES = {"STYLE_PATTERN", "AUDIT_ONLY"}
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
MODES = (
    "REACTION",
    "ALIGNMENT",
    "PLAY",
    "FOLLOW_UP",
    "SPECULATION",
    "CARE",
    "COORDINATION",
)
RESPONSE_MOVE_PROVENANCES = {
    "FRESH_VERIFIED",
    "HEURISTIC_OBSERVED",
    "FRESH_REJECTED",
    "NONE",
}
RESPONSE_MOVES_BY_MODE = {
    "REACTION": {"REACTION_GOOD_NEWS", "REACTION_SURPRISE", "REACTION_FUNNY"},
    "ALIGNMENT": {"ALIGNMENT_COMPLAINT", "ALIGNMENT_LOW_ENERGY"},
    "PLAY": {"PLAY_COMPETITIVE_TEASE", "PLAY_FRIENDLY_TEASE", "PLAY_LIGHT_EXAGGERATION"},
    "FOLLOW_UP": {"FOLLOW_UP_STATUS", "FOLLOW_UP_PROGRESS", "FOLLOW_UP_CHANGE", "FOLLOW_UP_CAUSE"},
    "SPECULATION": {"SPECULATION_CAUSE", "SPECULATION_FUTURE", "SPECULATION_PRESENT"},
    "CARE": {"CARE_PHYSICAL", "CARE_FATIGUE", "CARE_EMOTIONAL"},
    "COORDINATION": {"COORDINATION_CHOICE", "COORDINATION_TIME", "COORDINATION_ACTION", "COORDINATION_ROLE"},
}
RESPONSE_FORMS_BY_MODE = {
    "REACTION": {"EXPRESSIVE"},
    "ALIGNMENT": {"ALIGN_AND_ADD"},
    "PLAY": {"PLAYFUL_RETURN"},
    "FOLLOW_UP": {"QUESTION"},
    "SPECULATION": {"HEDGED_GUESS"},
    "CARE": {"SUPPORTIVE"},
    "COORDINATION": {"PROPOSAL", "QUESTION"},
}
RESPONSE_RHYTHM_BY_MODE = {
    "REACTION": {"SHORT_REACTION", "LAUGHTER", "POSITIVE_ACKNOWLEDGMENT"},
    "ALIGNMENT": {"AGREE_AND_ADD", "SHARED_FEELING"},
    "PLAY": {"LAUGHTER", "PLAYFUL_RETURN", "LIGHT_EXAGGERATION"},
    "FOLLOW_UP": {"DIRECT_QUESTION"},
    "SPECULATION": {"HEDGED_GUESS"},
    "CARE": {"GENTLE_CARE", "SUPPORTIVE_NUDGE"},
    "COORDINATION": {"ACTION_PROPOSAL", "COORDINATION_CHECK"},
}
SCENE_TRAITS_BY_MODE = {
    "REACTION": {"REACTION_GOOD_NEWS", "REACTION_SURPRISE_OR_FUNNY"},
    "ALIGNMENT": {"ALIGNMENT_COMPLAINT_OR_LOW_ENERGY"},
    "PLAY": {"PLAY_BANTER"},
    "FOLLOW_UP": {"FOLLOW_UP_STATUS_OR_PROGRESS", "FOLLOW_UP_CHANGE", "FOLLOW_UP_CAUSE"},
    "SPECULATION": {"SPECULATION_CAUSE", "SPECULATION_FUTURE", "SPECULATION_PRESENT"},
    "CARE": {"CARE_PHYSICAL_CONDITION", "CARE_FATIGUE_OVERLOAD", "CARE_EMOTIONAL_DISTRESS"},
    "COORDINATION": {"COORDINATION_CHOICE", "COORDINATION_TIME", "COORDINATION_ACTION_PROPOSAL", "COORDINATION_ROLE_OR_ORDER"},
}
PROVIDER_STYLE_CUES_BY_MODE = {
    "REACTION": {"REACTION_IMMEDIATE", "REACTION_LAUGH_ALONG", "REACTION_WARM_ACK"},
    "ALIGNMENT": {"ALIGNMENT_LOW_KEY_ACK", "ALIGNMENT_SHARED_FEELING"},
    "PLAY": {"PLAY_COUNTERTEASE", "PLAY_LIGHT_EXAGGERATION"},
    "FOLLOW_UP": {"FOLLOW_UP_SOFT_CHECK", "FOLLOW_UP_DIRECT_CHECK"},
    "SPECULATION": {"SPECULATION_LIGHT_HEDGE"},
    "CARE": {"CARE_GENTLE_VALIDATE", "CARE_SOFT_NUDGE"},
    "COORDINATION": {"COORDINATION_CONFIRM", "COORDINATION_PROPOSE", "COORDINATION_ASK_ONE"},
}
PROVIDER_STYLE_CUE_RULES_BY_MODE = {
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
MAX_SCENE_TRAITS = 2
MAX_PROVIDER_STYLE_CUES = 1
MAX_RESPONSE_RHYTHM_CUES = 8
REQUIRED_CARD_KEYS = {
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
    "prompt_eligible",
    "prompt_surface",
    "response_surface_has_card_local_alias",
    "response_move",
    "response_move_provenance",
    "response_form",
    "response_rhythm",
    "scene_traits",
    "provider_style_cues",
    "embedding_model",
}
CANDIDATE_MANIFEST_KEYS = {
    "schema",
    "input_jsonl_sha256",
    "record_count",
    "jsonl_sha256",
    "prompt_eligible_count",
    "prompt_disabled_count",
    "prompt_eligible_by_response_mode",
    "prompt_ineligible_reason_counts",
    "response_move_metadata_counts",
    "prompt_eligible_response_move_counts",
    "prompt_eligible_response_move_source_counts",
    "response_form_metadata_counts",
    "response_rhythm_cue_counts",
    "response_rhythm_coverage",
    "response_rhythm_behavior_coverage",
    "response_rhythm_delivery_only_count",
    "response_move_policy",
    "response_move_review_ledger_sha256",
    "fresh_verified_response_move_count",
    "heuristically_observed_response_move_count",
    "rejected_response_move_review_count",
    "scene_trait_counts",
    "provider_style_cue_counts",
    "response_move_provenance_counts",
    "retrieval_policy",
    "prompt_surface_policy",
    "source_fingerprint_count",
    "expected_source_count",
    "expected_source_fingerprint_set_sha256",
    "source_coverage_complete",
    "quality_counts",
    "purpose",
}
BLIND_QUALITY_REVIEW_KEYS = {
    "schema",
    "artifact_jsonl_sha256",
    "verdict",
    "case_count",
    "case_count_by_response_mode",
    "top1_useful_count",
    "top2_any_useful_count",
    "top1_useful_by_response_mode",
    "top2_any_useful_by_response_mode",
    "unsafe_provider_surface_count",
    "private_context_dependency_count",
    "copy_risk_count",
    "retrieval_audit_sha256",
}
RAG_VALUE_REVIEW_KEYS = {
    "schema",
    "artifact_jsonl_sha256",
    "retrieval_audit_sha256",
    "review_protocol",
    "reviewer_type",
    "verdict",
    "case_count",
    "case_count_by_response_mode",
    "top1_value_add_count",
    "top2_any_value_add_count",
    "top1_value_add_by_response_mode",
    "top2_any_value_add_by_response_mode",
    "worse_than_mode_baseline_count",
    "unsupported_specificity_count",
    "unsafe_provider_surface_count",
}
RETRIEVAL_AUDIT_KEYS = {
    "schema",
    "candidate_jsonl_sha256",
    "retrieval_policy",
    "embedding_provider",
    "embedding_model",
    "execution_scope",
    "fixed_probe",
    "independent_holdout",
    "verdict",
    "reason_codes",
}
FIXED_PROBE_AUDIT_KEYS = {
    "case_count",
    "exact_mode_return_case_count",
    "policy_abstention_count",
    "returned_reference_card_count",
    "returned_reference_case_count",
    "unexpected_post_query_empty_count",
}
INDEPENDENT_HOLDOUT_AUDIT_KEYS = FIXED_PROBE_AUDIT_KEYS | {"source_diverse_top2_case_count"}
SHA256_HEX = re.compile(r"[0-9a-f]{64}")
MIN_BLIND_CASES = 70
MIN_BLIND_CASES_PER_MODE = 10
MIN_RAG_VALUE_CASES = 70
MIN_RAG_VALUE_CASES_PER_MODE = 10
MIN_TOP1_VALUE_ADD_PERCENT = 60
MIN_TOP2_VALUE_ADD_PERCENT = 75
MIN_MODE_TOP1_VALUE_ADD_PERCENT = 50
MIN_MODE_TOP2_VALUE_ADD_PERCENT = 70
MIN_RETRIEVAL_AUDIT_FIXED_PROBES = 35
MIN_RETRIEVAL_AUDIT_HOLDOUTS = 35
MIN_RETRIEVAL_AUDIT_REFERENCE_COVERAGE_PERCENT = 80
RETRIEVAL_AUDIT_EMBEDDING_PROVIDER = "openai"
RETRIEVAL_AUDIT_EMBEDDING_MODEL = "text-embedding-3-small"
RETRIEVAL_AUDIT_EXECUTION_SCOPE = "ephemeral_h2_only_no_judge_no_discord_no_provider_generation"
DEFAULT_SOURCE_COVERAGE_POLICY = Path(__file__).resolve().parents[1] / "central-server/src/main/resources/human-speech-style-source-coverage.json"


def main() -> int:
    args = parse_args()
    candidate_directory = args.candidate_dir.resolve()
    source_jsonl = candidate_directory / "human-speech-style-cards.jsonl"
    source_manifest = candidate_directory / "manifest.json"
    if not source_jsonl.is_file() or not source_manifest.is_file():
        raise ValueError("private runtime candidate artifact is incomplete")

    manifest = read_json(source_manifest)
    records = read_records(source_jsonl)
    source_coverage_policy = read_source_coverage_policy(args.source_coverage_policy)
    validate_candidate(manifest, source_jsonl, records, source_coverage_policy)
    candidate_digest = sha256_file(source_jsonl)
    retrieval_audit, retrieval_audit_sha256 = validate_retrieval_audit(args.retrieval_audit, candidate_digest)
    blind_quality_review = validate_blind_quality_review(
        args.blind_quality_review,
        candidate_digest,
        retrieval_audit_sha256,
    )
    rag_value_review, rag_value_review_sha256 = validate_rag_value_review(
        args.rag_value_review,
        candidate_digest,
        retrieval_audit_sha256,
    )

    output_directory = args.output_dir.resolve()
    output_directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(output_directory, 0o700)
    output_jsonl = output_directory / "human-speech-style-cards.jsonl"
    copy_private_file(source_jsonl, output_jsonl)
    # Startup import는 JSONL 단독을 받지 않는다. 이 네 증거 파일을 같은 protected directory에 함께 봉인해
    # runner가 candidate digest와 안전·실검색·고정 enum baseline 대비 품질 증거를 다시 확인할 수 있게 한다.
    copy_private_file(source_manifest, output_directory / "candidate-manifest.json")
    copy_private_file(args.retrieval_audit, output_directory / "retrieval-audit.json")
    copy_private_file(args.blind_quality_review, output_directory / "blind-quality-review.json")
    copy_private_file(args.rag_value_review, output_directory / "rag-value-review.json")

    quality = records[0]["quality"]
    consent_revision = records[0]["consent_revision"]
    prompt_eligible = [record for record in records if record["prompt_eligible"]]
    scene_trait_counts = count_scene_traits(records)
    provider_style_cue_counts = count_provider_style_cues(records)
    response_move_provenance_counts = count_response_move_provenances(records)
    output_manifest = {
        "schema": IMPORT_MANIFEST_SCHEMA,
        "quality": quality,
        "consent_revision": consent_revision,
        "record_count": len(records),
        "accepted_card_count": len(records),
        "prompt_eligible_count": len(prompt_eligible),
        "prompt_disabled_count": len(records) - len(prompt_eligible),
        "prompt_eligible_by_response_mode": dict(
            sorted(Counter(record["response_mode"] for record in prompt_eligible).items()),
        ),
        "prompt_surface_counts": dict(sorted(Counter(record["prompt_surface"] for record in records).items())),
        "scene_trait_counts": scene_trait_counts,
        "provider_style_cue_counts": provider_style_cue_counts,
        "response_move_provenance_counts": response_move_provenance_counts,
        "source_count": len({record["source_fingerprint"] for record in records}),
        "source_fingerprint_count": len({record["source_fingerprint"] for record in records}),
        "expected_source_count": source_coverage_policy["source_count"],
        "expected_source_fingerprint_set_sha256": source_coverage_policy["source_fingerprint_set_sha256"],
        "source_coverage_complete": True,
        "response_rhythm_coverage": sum(bool(record["response_rhythm"]) for record in records),
        "jsonl_sha256": sha256_file(output_jsonl),
        "input_candidate_manifest_sha256": sha256_file(source_manifest),
        "input_candidate_jsonl_sha256": sha256_file(source_jsonl),
        "all_cards_formally_approved": quality == FORMAL_QUALITY,
        "all_cards_user_released": quality == USER_RELEASED_QUALITY,
        "retrieval_policy": RETRIEVAL_POLICY,
        "response_move_policy": manifest["response_move_policy"],
        "response_move_review_ledger_sha256": manifest["response_move_review_ledger_sha256"],
        "fresh_verified_response_move_count": manifest["fresh_verified_response_move_count"],
        "heuristically_observed_response_move_count": manifest["heuristically_observed_response_move_count"],
        "rejected_response_move_review_count": manifest["rejected_response_move_review_count"],
        "prompt_surface_policy": PROMPT_SURFACE_POLICY,
        "retrieval_audit": retrieval_audit,
        "retrieval_audit_sha256": retrieval_audit_sha256,
        "blind_quality_review": blind_quality_review,
        "rag_value_review": rag_value_review,
        "rag_value_review_sha256": rag_value_review_sha256,
        "purpose": "private one-shot Speech-style RAG import; all cards stay encrypted for audit and only closed de-identified style patterns derived from observed response metadata are searchable within each response mode after sealed actual-retrieval, blind-quality, and fixed-mode-baseline value PASS evidence",
    }
    write_private_json(output_directory / "manifest.json", output_manifest)
    print(
        "human-speech-style import artifact prepared "
        f"records={len(records)} prompt_eligible={len(prompt_eligible)} "
        f"prompt_disabled={len(records) - len(prompt_eligible)} "
        f"sources={output_manifest['source_count']} quality={quality}",
    )
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate-dir", type=Path, required=True, help="private materialized runtime-candidate directory")
    parser.add_argument("--output-dir", type=Path, required=True, help="private protected one-shot import directory")
    parser.add_argument(
        "--source-coverage-policy",
        type=Path,
        default=DEFAULT_SOURCE_COVERAGE_POLICY,
        help="trusted private corpus source-set coverage policy",
    )
    parser.add_argument(
        "--blind-quality-review",
        type=Path,
        required=True,
        help="private aggregate-only blind quality PASS review bound to the candidate JSONL digest",
    )
    parser.add_argument(
        "--retrieval-audit",
        type=Path,
        required=True,
        help="private aggregate-only successful actual OpenAI retrieval audit bound to the candidate JSONL digest",
    )
    parser.add_argument(
        "--rag-value-review",
        type=Path,
        required=True,
        help="private fresh comparative PASS review showing actual retrieved patterns add value over a fixed response-mode baseline",
    )
    return parser.parse_args()


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("private runtime candidate manifest is invalid") from error
    if not isinstance(value, dict):
        raise ValueError("private runtime candidate manifest is invalid")
    return value


def read_source_coverage_policy(path: Path) -> dict[str, Any]:
    try:
        policy = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("human speech style source coverage policy could not be read") from error
    if not isinstance(policy, dict) or set(policy) != {"schema", "source_count", "source_fingerprint_set_sha256"}:
        raise ValueError("human speech style source coverage policy fields are invalid")
    if policy.get("schema") != "nia-human-speech-style-source-coverage.v1":
        raise ValueError("human speech style source coverage policy schema is invalid")
    if not isinstance(policy.get("source_count"), int) or policy["source_count"] < 1:
        raise ValueError("human speech style source coverage policy source count is invalid")
    if not isinstance(policy.get("source_fingerprint_set_sha256"), str) or not SHA256_HEX.fullmatch(
        policy["source_fingerprint_set_sha256"],
    ):
        raise ValueError("human speech style source coverage policy fingerprint digest is invalid")
    return policy


def read_records(path: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError as error:
            raise ValueError(f"private runtime candidate card is invalid at line {line_number}") from error
        if not isinstance(record, dict) or set(record) != REQUIRED_CARD_KEYS:
            raise ValueError(f"private runtime candidate card is incomplete at line {line_number}")
        records.append(record)
    if not records:
        raise ValueError("private runtime candidate is empty")
    return records


def validate_candidate(
    manifest: dict[str, Any],
    jsonl_path: Path,
    records: list[dict[str, Any]],
    source_coverage_policy: dict[str, Any],
) -> None:
    if set(manifest) != CANDIDATE_MANIFEST_KEYS:
        raise ValueError("private runtime candidate manifest fields are invalid")
    if manifest.get("schema") != CANDIDATE_MANIFEST_SCHEMA:
        raise ValueError("private runtime candidate manifest schema is unsupported")
    if manifest.get("record_count") != len(records):
        raise ValueError("private runtime candidate record count mismatch")
    if manifest.get("jsonl_sha256") != sha256_file(jsonl_path):
        raise ValueError("private runtime candidate JSONL digest mismatch")
    if manifest.get("retrieval_policy") != RETRIEVAL_POLICY:
        raise ValueError("private runtime candidate retrieval policy is invalid")
    if manifest.get("response_move_policy") != RESPONSE_MOVE_POLICY:
        raise ValueError("private runtime candidate response-move policy is invalid")
    response_move_review_digest = manifest.get("response_move_review_ledger_sha256")
    if not isinstance(response_move_review_digest, str) or not SHA256_HEX.fullmatch(response_move_review_digest):
        raise ValueError("private runtime candidate response-move review ledger is invalid")
    if manifest.get("prompt_surface_policy") != PROMPT_SURFACE_POLICY:
        raise ValueError("private runtime candidate prompt surface policy is invalid")
    if len({record["example_id"] for record in records}) != len(records):
        raise ValueError("private runtime candidate example ids are duplicated")
    if any(record["schema"] != IMPORT_CARD_SCHEMA for record in records):
        raise ValueError("private runtime candidate card schema is invalid")

    qualities = {record["quality"] for record in records}
    consent_revisions = {record["consent_revision"] for record in records}
    if len(qualities) != 1 or qualities.pop() not in ALLOWED_QUALITIES:
        raise ValueError("private runtime candidate quality is invalid")
    if len(consent_revisions) != 1:
        raise ValueError("private runtime candidate consent revision is inconsistent")
    if manifest.get("quality_counts") != dict(sorted(Counter(record["quality"] for record in records).items())):
        raise ValueError("private runtime candidate quality count mismatch")

    prompt_eligible = [record for record in records if record["prompt_eligible"] is True]
    if manifest.get("prompt_eligible_count") != len(prompt_eligible):
        raise ValueError("private runtime candidate eligible count mismatch")
    if manifest.get("prompt_disabled_count") != len(records) - len(prompt_eligible):
        raise ValueError("private runtime candidate disabled count mismatch")
    if not prompt_eligible:
        raise ValueError("private runtime candidate has no searchable cards")
    source_fingerprints = {record["source_fingerprint"] for record in records}
    if manifest.get("source_fingerprint_count") != len(source_fingerprints):
        raise ValueError("private runtime candidate source count mismatch")
    if manifest.get("expected_source_count") != source_coverage_policy["source_count"]:
        raise ValueError("private runtime candidate source coverage count is invalid")
    if manifest.get("expected_source_fingerprint_set_sha256") != source_coverage_policy["source_fingerprint_set_sha256"]:
        raise ValueError("private runtime candidate source coverage set is invalid")
    if manifest.get("source_coverage_complete") is not True:
        raise ValueError("private runtime candidate source coverage is incomplete")
    source_fingerprint_set_sha256 = hashlib.sha256("\n".join(sorted(source_fingerprints)).encode()).hexdigest()
    if source_fingerprint_set_sha256 != source_coverage_policy["source_fingerprint_set_sha256"]:
        raise ValueError("private runtime candidate does not match required source coverage set")

    for record in records:
        if record["response_mode"] not in MODES:
            raise ValueError("private runtime candidate response mode is invalid")
        validate_card_contract(record)
        if not isinstance(record["prompt_eligible"], bool):
            raise ValueError("private runtime candidate eligibility is invalid")
        prompt_surface = record["prompt_surface"]
        if prompt_surface not in PROMPT_SURFACES:
            raise ValueError("private runtime candidate prompt surface is invalid")
        if record["prompt_eligible"] != (prompt_surface == "STYLE_PATTERN"):
            raise ValueError("private runtime candidate prompt surface and eligibility disagree")
        if not isinstance(record["response_surface_has_card_local_alias"], bool):
            raise ValueError("private runtime candidate alias safety flag is invalid")
        validate_card_metadata(record)
        if record["prompt_eligible"]:
            if record["response_form"] is None:
                raise ValueError("private runtime candidate style pattern has no response form")
            if not any(cue not in DELIVERY_ONLY_RHYTHM_CUES for cue in record["response_rhythm"]):
                raise ValueError("private runtime candidate style pattern has no observed response rhythm")
            if len(record["provider_style_cues"]) != 1:
                raise ValueError("private runtime candidate style pattern must have exactly one provider style cue")

    scene_trait_counts = count_scene_traits(records)
    if manifest.get("scene_trait_counts") != scene_trait_counts:
        raise ValueError("private runtime candidate scene-trait count mismatch")
    provider_style_cue_counts = count_provider_style_cues(records)
    if manifest.get("provider_style_cue_counts") != provider_style_cue_counts:
        raise ValueError("private runtime candidate provider-style-cue count mismatch")
    response_move_provenance_counts = count_response_move_provenances(records)
    if manifest.get("response_move_provenance_counts") != response_move_provenance_counts:
        raise ValueError("private runtime candidate response-move provenance count mismatch")
    if sum(response_move_provenance_counts.values()) != len(records):
        raise ValueError("private runtime candidate response-move provenance count mismatch")

    observed_response_moves = sum(record["response_move"] is not None for record in records)
    fresh_verified_response_moves = response_move_provenance_counts.get("FRESH_VERIFIED", 0)
    heuristically_observed_response_moves = response_move_provenance_counts.get("HEURISTIC_OBSERVED", 0)
    rejected_response_moves = response_move_provenance_counts.get("FRESH_REJECTED", 0)
    no_response_move_count = response_move_provenance_counts.get("NONE", 0)
    if fresh_verified_response_moves <= 0:
        raise ValueError("private runtime candidate has no fresh verified response-move evidence")
    if (
        fresh_verified_response_moves + heuristically_observed_response_moves != observed_response_moves
        or rejected_response_moves + no_response_move_count != len(records) - observed_response_moves
    ):
        raise ValueError("private runtime candidate response-move provenance count mismatch")
    expected_provenance_aggregate_counts = {
        "fresh_verified_response_move_count": fresh_verified_response_moves,
        "heuristically_observed_response_move_count": heuristically_observed_response_moves,
        "rejected_response_move_review_count": rejected_response_moves,
    }
    for key, expected_count in expected_provenance_aggregate_counts.items():
        actual_count = manifest.get(key)
        if (
            not isinstance(actual_count, int)
            or isinstance(actual_count, bool)
            or actual_count != expected_count
        ):
            raise ValueError("private runtime candidate response-move provenance count mismatch")

    prompt_eligible_by_response_mode = dict(
        sorted(Counter(record["response_mode"] for record in prompt_eligible).items()),
    )
    if set(prompt_eligible_by_response_mode) != set(MODES):
        raise ValueError("private runtime candidate does not cover every response mode with searchable cards")
    if manifest.get("prompt_eligible_by_response_mode") != prompt_eligible_by_response_mode:
        raise ValueError("private runtime candidate searchable response-mode count mismatch")


def validate_card_contract(record: dict[str, Any]) -> None:
    if not isinstance(record["example_id"], str) or not re.fullmatch(r"human-style-[0-9]{6}", record["example_id"]):
        raise ValueError("private runtime candidate example id is invalid")
    if not isinstance(record["situation"], str) or not record["situation"].strip() or len(record["situation"]) > 240:
        raise ValueError("private runtime candidate situation is invalid")
    signals = record["style_signals"]
    if (
        not isinstance(signals, list)
        or len(signals) > 12
        or not all(isinstance(signal, str) and signal.strip() and len(signal) <= 80 for signal in signals)
    ):
        raise ValueError("private runtime candidate style signals are invalid")
    for bubble_key in ("context_bubbles", "response_bubbles"):
        bubbles = record[bubble_key]
        if not isinstance(bubbles, list) or not bubbles or len(bubbles) > 12:
            raise ValueError("private runtime candidate bubbles are invalid")
        if not all(
            isinstance(bubble, dict)
            and set(bubble) == {"speaker", "text"}
            and isinstance(bubble["speaker"], str)
            and bubble["speaker"].strip()
            and isinstance(bubble["text"], str)
            and bubble["text"].strip()
            and len(bubble["text"]) <= 350
            for bubble in bubbles
        ):
            raise ValueError("private runtime candidate bubble is invalid")
    if not isinstance(record["combined_chars"], int) or isinstance(record["combined_chars"], bool) or not 1 <= record["combined_chars"] <= 350:
        raise ValueError("private runtime candidate combined chars are invalid")
    if not isinstance(record["source_fingerprint"], str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", record["source_fingerprint"]):
        raise ValueError("private runtime candidate source fingerprint is invalid")
    if not isinstance(record["consent_revision"], str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,96}", record["consent_revision"]):
        raise ValueError("private runtime candidate consent revision is invalid")
    if record["embedding_model"] != RETRIEVAL_AUDIT_EMBEDDING_MODEL:
        raise ValueError("private runtime candidate embedding model is invalid")


def validate_card_metadata(record: dict[str, Any]) -> None:
    response_mode = record["response_mode"]
    response_move = validate_optional_mode_enum(
        record["response_move"],
        RESPONSE_MOVES_BY_MODE[response_mode],
        all_enum_names(RESPONSE_MOVES_BY_MODE),
        "response move",
    )
    response_form = validate_optional_mode_enum(
        record["response_form"],
        RESPONSE_FORMS_BY_MODE[response_mode],
        all_enum_names(RESPONSE_FORMS_BY_MODE),
        "response form",
    )
    provenance = record["response_move_provenance"]
    if not isinstance(provenance, str) or provenance not in RESPONSE_MOVE_PROVENANCES:
        raise ValueError("private runtime candidate response-move provenance is invalid")
    if provenance in {"FRESH_VERIFIED", "HEURISTIC_OBSERVED"} and response_move is None:
        raise ValueError("private runtime candidate response-move provenance does not match response move")
    if provenance in {"FRESH_REJECTED", "NONE"} and response_move is not None:
        raise ValueError("private runtime candidate response-move provenance does not match response move")

    rhythm = record["response_rhythm"]
    if (
        not isinstance(rhythm, list)
        or len(rhythm) > MAX_RESPONSE_RHYTHM_CUES
        or not all(isinstance(cue, str) and cue.strip() for cue in rhythm)
        or len(set(rhythm)) != len(rhythm)
    ):
        raise ValueError("private runtime candidate response rhythm metadata is invalid")
    allowed_rhythm = RESPONSE_RHYTHM_BY_MODE[response_mode] | DELIVERY_ONLY_RHYTHM_CUES
    all_rhythm = all_enum_names(RESPONSE_RHYTHM_BY_MODE) | DELIVERY_ONLY_RHYTHM_CUES
    for cue in rhythm:
        validate_optional_mode_enum(cue, allowed_rhythm, all_rhythm, "response rhythm cue")

    provider_style_cues = record["provider_style_cues"]
    if (
        not isinstance(provider_style_cues, list)
        or len(provider_style_cues) > MAX_PROVIDER_STYLE_CUES
        or not all(isinstance(cue, str) and cue.strip() for cue in provider_style_cues)
        or len(set(provider_style_cues)) != len(provider_style_cues)
    ):
        raise ValueError("private runtime candidate provider style cues are invalid")
    all_provider_style_cues = all_enum_names(PROVIDER_STYLE_CUES_BY_MODE)
    for cue in provider_style_cues:
        validate_optional_mode_enum(
            cue,
            PROVIDER_STYLE_CUES_BY_MODE[response_mode],
            all_provider_style_cues,
            "provider style cue",
        )
    if provider_style_cues != derive_provider_style_cues(response_mode, response_form, rhythm):
        raise ValueError("private runtime candidate provider style cues do not match observed response metadata")

    scene_traits = record["scene_traits"]
    if (
        not isinstance(scene_traits, list)
        or len(scene_traits) > MAX_SCENE_TRAITS
        or not all(isinstance(trait, str) and trait.strip() for trait in scene_traits)
        or len(set(scene_traits)) != len(scene_traits)
    ):
        raise ValueError("private runtime candidate scene traits are invalid")
    all_scene_traits = all_enum_names(SCENE_TRAITS_BY_MODE)
    for trait in scene_traits:
        validate_optional_mode_enum(
            trait,
            SCENE_TRAITS_BY_MODE[response_mode],
            all_scene_traits,
            "scene trait",
        )


def validate_optional_mode_enum(
    value: Any,
    compatible_names: set[str],
    all_names: set[str],
    label: str,
) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str) or not value.strip() or value not in all_names:
        raise ValueError(f"private runtime candidate {label} enum is invalid")
    if value not in compatible_names:
        raise ValueError(f"private runtime candidate {label} does not match response mode")
    return value


def all_enum_names(names_by_mode: dict[str, set[str]]) -> set[str]:
    return set().union(*names_by_mode.values())


def count_scene_traits(records: list[dict[str, Any]]) -> dict[str, int]:
    return dict(sorted(Counter(trait for record in records for trait in record["scene_traits"]).items()))


def count_provider_style_cues(records: list[dict[str, Any]]) -> dict[str, int]:
    return dict(sorted(Counter(cue for record in records for cue in record["provider_style_cues"]).items()))


def count_response_move_provenances(records: list[dict[str, Any]]) -> dict[str, int]:
    return dict(sorted(Counter(record["response_move_provenance"] for record in records).items()))


def derive_provider_style_cues(
    response_mode: str,
    response_form: str | None,
    response_rhythm: list[str],
) -> list[str]:
    return [
        cue
        for cue, compatible_forms, compatible_rhythm in PROVIDER_STYLE_CUE_RULES_BY_MODE[response_mode]
        if response_form in compatible_forms or compatible_rhythm.intersection(response_rhythm)
    ][:MAX_PROVIDER_STYLE_CUES]


def validate_retrieval_audit(path: Path, expected_artifact_digest: str) -> tuple[dict[str, Any], str]:
    if not path.is_file():
        raise ValueError("private retrieval audit is required")
    try:
        audit = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("private retrieval audit is invalid") from error
    if not isinstance(audit, dict) or set(audit) != RETRIEVAL_AUDIT_KEYS:
        raise ValueError("private retrieval audit fields are invalid")
    if audit.get("schema") != RETRIEVAL_AUDIT_SCHEMA:
        raise ValueError("private retrieval audit schema is invalid")
    if audit.get("candidate_jsonl_sha256") != expected_artifact_digest:
        raise ValueError("private retrieval audit artifact digest does not match the candidate")
    if audit.get("retrieval_policy") != RETRIEVAL_POLICY:
        raise ValueError("private retrieval audit retrieval policy is invalid")
    if audit.get("embedding_provider") != RETRIEVAL_AUDIT_EMBEDDING_PROVIDER:
        raise ValueError("private retrieval audit embedding provider is invalid")
    if audit.get("embedding_model") != RETRIEVAL_AUDIT_EMBEDDING_MODEL:
        raise ValueError("private retrieval audit embedding model is invalid")
    if audit.get("execution_scope") != RETRIEVAL_AUDIT_EXECUTION_SCOPE:
        raise ValueError("private retrieval audit execution scope is invalid")
    if audit.get("verdict") != "PASS":
        raise ValueError("private retrieval audit does not pass quality gates")
    if audit.get("reason_codes") != []:
        raise ValueError("private retrieval audit has unresolved findings")

    fixed_probe = validate_audit_suite(
        audit.get("fixed_probe"),
        FIXED_PROBE_AUDIT_KEYS,
        "fixed probe",
        MIN_RETRIEVAL_AUDIT_FIXED_PROBES,
    )
    independent_holdout = validate_audit_suite(
        audit.get("independent_holdout"),
        INDEPENDENT_HOLDOUT_AUDIT_KEYS,
        "independent holdout",
        MIN_RETRIEVAL_AUDIT_HOLDOUTS,
    )
    if independent_holdout["returned_reference_case_count"] * 100 < (
        independent_holdout["case_count"] * MIN_RETRIEVAL_AUDIT_REFERENCE_COVERAGE_PERCENT
    ):
        raise ValueError("private retrieval audit independent holdout coverage is insufficient")
    if independent_holdout["source_diverse_top2_case_count"] != independent_holdout["returned_reference_case_count"]:
        raise ValueError("private retrieval audit independent holdout source diversity is insufficient")
    return (
        {
            "schema": RETRIEVAL_AUDIT_SCHEMA,
            "candidate_jsonl_sha256": expected_artifact_digest,
            "retrieval_policy": RETRIEVAL_POLICY,
            "embedding_provider": RETRIEVAL_AUDIT_EMBEDDING_PROVIDER,
            "embedding_model": RETRIEVAL_AUDIT_EMBEDDING_MODEL,
            "execution_scope": RETRIEVAL_AUDIT_EXECUTION_SCOPE,
            "fixed_probe": fixed_probe,
            "independent_holdout": independent_holdout,
            "verdict": "PASS",
            "reason_codes": [],
        },
        sha256_file(path),
    )


def validate_audit_suite(
    value: Any,
    expected_fields: set[str],
    label: str,
    minimum_case_count: int,
) -> dict[str, int]:
    if not isinstance(value, dict) or set(value) != expected_fields:
        raise ValueError(f"private retrieval audit {label} fields are invalid")
    suite = {field: require_nonnegative_int(value[field], f"retrieval audit {label} {field}") for field in expected_fields}
    if suite["case_count"] < minimum_case_count:
        raise ValueError(f"private retrieval audit {label} coverage is insufficient")
    if suite["returned_reference_case_count"] > suite["case_count"]:
        raise ValueError(f"private retrieval audit {label} reference count is invalid")
    if suite["exact_mode_return_case_count"] != suite["returned_reference_case_count"]:
        raise ValueError(f"private retrieval audit {label} response mode preservation is invalid")
    if suite["policy_abstention_count"] > suite["case_count"]:
        raise ValueError(f"private retrieval audit {label} abstention count is invalid")
    if suite["unexpected_post_query_empty_count"] != 0:
        raise ValueError(f"private retrieval audit {label} has post-query empty results")
    return dict(sorted(suite.items()))


def validate_blind_quality_review(
    path: Path,
    expected_artifact_digest: str,
    expected_retrieval_audit_digest: str,
) -> dict[str, Any]:
    if not path.is_file():
        raise ValueError("private blind quality review is required")
    try:
        review = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("private blind quality review is invalid") from error
    if not isinstance(review, dict) or set(review) != BLIND_QUALITY_REVIEW_KEYS:
        raise ValueError("private blind quality review fields are invalid")
    if review.get("schema") != BLIND_QUALITY_REVIEW_SCHEMA:
        raise ValueError("private blind quality review schema is invalid")
    artifact_digest = review.get("artifact_jsonl_sha256")
    if not isinstance(artifact_digest, str) or not SHA256_HEX.fullmatch(artifact_digest):
        raise ValueError("private blind quality review artifact digest is invalid")
    if artifact_digest != expected_artifact_digest:
        raise ValueError("private blind quality review artifact digest does not match the candidate")
    retrieval_audit_digest = review.get("retrieval_audit_sha256")
    if not isinstance(retrieval_audit_digest, str) or not SHA256_HEX.fullmatch(retrieval_audit_digest):
        raise ValueError("private blind quality review retrieval audit digest is invalid")
    if retrieval_audit_digest != expected_retrieval_audit_digest:
        raise ValueError("private blind quality review is not bound to the retrieval audit")
    if review.get("verdict") != "PASS":
        raise ValueError("private blind quality review does not pass quality gates")

    case_counts = validate_mode_counts(review.get("case_count_by_response_mode"), "case count")
    top1_counts = validate_mode_counts(review.get("top1_useful_by_response_mode"), "top-1 usefulness")
    top2_counts = validate_mode_counts(review.get("top2_any_useful_by_response_mode"), "top-2 usefulness")
    case_count = require_nonnegative_int(review.get("case_count"), "case count")
    top1_count = require_nonnegative_int(review.get("top1_useful_count"), "top-1 usefulness")
    top2_count = require_nonnegative_int(review.get("top2_any_useful_count"), "top-2 usefulness")
    if case_count != sum(case_counts.values()) or case_count < MIN_BLIND_CASES:
        raise ValueError("private blind quality review case coverage is insufficient")
    if any(count < MIN_BLIND_CASES_PER_MODE for count in case_counts.values()):
        raise ValueError("private blind quality review response mode coverage is insufficient")
    if top1_count != sum(top1_counts.values()) or top2_count != sum(top2_counts.values()):
        raise ValueError("private blind quality review usefulness counts are inconsistent")
    if top1_count > top2_count or top2_count > case_count:
        raise ValueError("private blind quality review usefulness counts are invalid")
    if top1_count * 100 < case_count * 65 or top2_count * 100 < case_count * 80:
        raise ValueError("private blind quality review does not meet overall usefulness thresholds")

    for mode in MODES:
        mode_cases = case_counts[mode]
        top1_threshold = percentage_ceiling(mode_cases, 60 if mode == "COORDINATION" else 50)
        top2_threshold = percentage_ceiling(mode_cases, 80 if mode == "COORDINATION" else 70)
        if top1_counts[mode] > top2_counts[mode] or top2_counts[mode] > mode_cases:
            raise ValueError("private blind quality review response mode usefulness counts are invalid")
        if top1_counts[mode] < top1_threshold or top2_counts[mode] < top2_threshold:
            raise ValueError("private blind quality review does not meet response mode usefulness thresholds")

    for key in (
        "unsafe_provider_surface_count",
        "private_context_dependency_count",
        "copy_risk_count",
    ):
        if require_nonnegative_int(review.get(key), key) != 0:
            raise ValueError("private blind quality review has unresolved safety findings")
    return review


def validate_rag_value_review(
    path: Path,
    expected_artifact_digest: str,
    expected_retrieval_audit_digest: str,
) -> tuple[dict[str, Any], str]:
    if not path.is_file():
        raise ValueError("private RAG value review is required")
    try:
        review = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("private RAG value review is invalid") from error
    if not isinstance(review, dict) or set(review) != RAG_VALUE_REVIEW_KEYS:
        raise ValueError("private RAG value review fields are invalid")
    if review.get("schema") != RAG_VALUE_REVIEW_SCHEMA:
        raise ValueError("private RAG value review schema is invalid")
    if review.get("review_protocol") != RAG_VALUE_REVIEW_PROTOCOL:
        raise ValueError("private RAG value review protocol is invalid")
    if review.get("reviewer_type") != RAG_VALUE_REVIEWER_TYPE:
        raise ValueError("private RAG value review reviewer is invalid")
    artifact_digest = review.get("artifact_jsonl_sha256")
    if not isinstance(artifact_digest, str) or not SHA256_HEX.fullmatch(artifact_digest):
        raise ValueError("private RAG value review artifact digest is invalid")
    if artifact_digest != expected_artifact_digest:
        raise ValueError("private RAG value review artifact digest does not match the candidate")
    retrieval_audit_digest = review.get("retrieval_audit_sha256")
    if not isinstance(retrieval_audit_digest, str) or not SHA256_HEX.fullmatch(retrieval_audit_digest):
        raise ValueError("private RAG value review retrieval audit digest is invalid")
    if retrieval_audit_digest != expected_retrieval_audit_digest:
        raise ValueError("private RAG value review is not bound to the retrieval audit")
    if review.get("verdict") != "PASS":
        raise ValueError("private RAG value review does not pass quality gates")

    case_counts = validate_rag_value_mode_counts(review.get("case_count_by_response_mode"), "case count")
    top1_counts = validate_rag_value_mode_counts(review.get("top1_value_add_by_response_mode"), "top-1 added value")
    top2_counts = validate_rag_value_mode_counts(review.get("top2_any_value_add_by_response_mode"), "top-2 added value")
    case_count = require_nonnegative_int(review.get("case_count"), "RAG value case count")
    top1_count = require_nonnegative_int(review.get("top1_value_add_count"), "RAG value top-1 added value")
    top2_count = require_nonnegative_int(review.get("top2_any_value_add_count"), "RAG value top-2 added value")
    if case_count != sum(case_counts.values()) or case_count < MIN_RAG_VALUE_CASES:
        raise ValueError("private RAG value review case coverage is insufficient")
    if any(count < MIN_RAG_VALUE_CASES_PER_MODE for count in case_counts.values()):
        raise ValueError("private RAG value review response mode coverage is insufficient")
    if top1_count != sum(top1_counts.values()) or top2_count != sum(top2_counts.values()):
        raise ValueError("private RAG value review counts are inconsistent")
    if top1_count > top2_count or top2_count > case_count:
        raise ValueError("private RAG value review counts are invalid")
    if top1_count * 100 < case_count * MIN_TOP1_VALUE_ADD_PERCENT or top2_count * 100 < case_count * MIN_TOP2_VALUE_ADD_PERCENT:
        raise ValueError("private RAG value review does not meet overall added-value thresholds")
    for mode in MODES:
        mode_cases = case_counts[mode]
        if top1_counts[mode] > top2_counts[mode] or top2_counts[mode] > mode_cases:
            raise ValueError("private RAG value review response mode counts are invalid")
        if top1_counts[mode] * 100 < mode_cases * MIN_MODE_TOP1_VALUE_ADD_PERCENT:
            raise ValueError("private RAG value review does not meet response mode top-1 threshold")
        if top2_counts[mode] * 100 < mode_cases * MIN_MODE_TOP2_VALUE_ADD_PERCENT:
            raise ValueError("private RAG value review does not meet response mode top-2 threshold")
    for key in (
        "worse_than_mode_baseline_count",
        "unsupported_specificity_count",
        "unsafe_provider_surface_count",
    ):
        if require_nonnegative_int(review.get(key), f"RAG value {key}") != 0:
            raise ValueError("private RAG value review has unresolved findings")
    return review, sha256_file(path)


def validate_rag_value_mode_counts(value: Any, label: str) -> dict[str, int]:
    if not isinstance(value, dict) or set(value) != set(MODES):
        raise ValueError(f"private RAG value review {label} mode map is invalid")
    return {mode: require_nonnegative_int(value[mode], f"RAG value {label} for {mode}") for mode in MODES}


def validate_mode_counts(value: Any, label: str) -> dict[str, int]:
    if not isinstance(value, dict) or set(value) != set(MODES):
        raise ValueError(f"private blind quality review {label} mode map is invalid")
    return {mode: require_nonnegative_int(value[mode], f"{label} for {mode}") for mode in MODES}


def require_nonnegative_int(value: Any, label: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise ValueError(f"private blind quality review {label} is invalid")
    return value


def percentage_ceiling(total: int, percent: int) -> int:
    return (total * percent + 99) // 100


def copy_private_file(source: Path, destination: Path) -> None:
    temporary = destination.with_name(f".{destination.name}.tmp")
    try:
        shutil.copyfile(source, temporary)
        os.chmod(temporary, 0o600)
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def write_private_json(path: Path, payload: dict[str, Any]) -> None:
    temporary = path.with_name(f".{path.name}.tmp")
    try:
        temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


if __name__ == "__main__":
    raise SystemExit(main())
