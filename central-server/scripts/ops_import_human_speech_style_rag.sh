#!/usr/bin/env bash
# Private Speech-style RAG를 별도 일회성 컨테이너로 적재한다.
# 이 스크립트는 production Environment secret이 주입된 CI job에서만 실행한다.
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-compose.yml}"
APP_SERVICE="${APP_SERVICE:-central-server}"
DB_SERVICE="${DB_SERVICE:-db}"
IMPORT_ARTIFACT="${IMPORT_ARTIFACT:-}"
IMPORT_ALLOWED_QUALITY="${IMPORT_ALLOWED_QUALITY:-CURATION_APPROVED}"
SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_COVERAGE_POLICY="$SCRIPT_DIRECTORY/../src/main/resources/human-speech-style-source-coverage.json"

fail() {
  echo "❌ $1" >&2
  exit 1
}

require_env() {
  local name="$1"
  [ -n "${!name:-}" ] || fail "required production secret is unavailable: $name"
}

require_private_permissions() {
  local path="$1"
  local type="$2"
  local mode
  case "$type" in
    directory) [ -d "$path" ] || fail "private directory is unavailable" ;;
    file) [ -f "$path" ] || fail "private artifact is unavailable" ;;
    *) fail "internal permission target type is invalid" ;;
  esac
  mode="$(stat -c '%a' "$path")"
  [ $((8#$mode & 077)) -eq 0 ] || fail "private $type permissions are too broad"
}

[ -f "$COMPOSE_FILE" ] || fail "compose file is unavailable"
[ -f "$SOURCE_COVERAGE_POLICY" ] || fail "trusted source coverage policy is unavailable"
[ -n "$IMPORT_ARTIFACT" ] || fail "IMPORT_ARTIFACT is required"
IMPORT_ARTIFACT="$(realpath -e "$IMPORT_ARTIFACT")"
IMPORT_DIRECTORY="$IMPORT_ARTIFACT"
IMPORT_RUN_USER="$(id -u):$(id -g)"
IMPORT_CARDS="$IMPORT_DIRECTORY/human-speech-style-cards.jsonl"
IMPORT_MANIFEST="$IMPORT_DIRECTORY/manifest.json"
IMPORT_CANDIDATE_MANIFEST="$IMPORT_DIRECTORY/candidate-manifest.json"
IMPORT_RETRIEVAL_AUDIT="$IMPORT_DIRECTORY/retrieval-audit.json"
IMPORT_BLIND_QUALITY_REVIEW="$IMPORT_DIRECTORY/blind-quality-review.json"
IMPORT_RAG_VALUE_REVIEW="$IMPORT_DIRECTORY/rag-value-review.json"

require_private_permissions "$IMPORT_DIRECTORY" directory
for protected_file in \
  "$IMPORT_CARDS" "$IMPORT_MANIFEST" "$IMPORT_CANDIDATE_MANIFEST" \
  "$IMPORT_RETRIEVAL_AUDIT" "$IMPORT_BLIND_QUALITY_REVIEW" "$IMPORT_RAG_VALUE_REVIEW"; do
  require_private_permissions "$protected_file" file
done

for required_secret in \
  CENTRAL_DB_PASSWORD DISCORD_BOT_TOKEN DISCORD_ENABLED RELAY_PUBLIC_URL CENTRAL_DURABLE_SECRET \
  NEXA_FIELD_ENC_KEY OPENAI_API_KEY CONNECT_DISCORD_CLIENT_ID CONNECT_DISCORD_CLIENT_SECRET \
  CENTRAL_OAUTH_ENABLED CENTRAL_DASHBOARD_ADMIN_USER_IDS CENTRAL_METRICS_SCRAPE_TOKEN; do
  require_env "$required_secret"
done

artifact_metadata="$(python3 - "$IMPORT_CARDS" "$IMPORT_MANIFEST" "$IMPORT_CANDIDATE_MANIFEST" "$IMPORT_RETRIEVAL_AUDIT" "$IMPORT_BLIND_QUALITY_REVIEW" "$IMPORT_RAG_VALUE_REVIEW" "$IMPORT_ALLOWED_QUALITY" "$SOURCE_COVERAGE_POLICY" <<'PY'
import hashlib
import json
import re
import sys
from pathlib import Path

artifact = Path(sys.argv[1])
manifest_path = Path(sys.argv[2])
candidate_manifest_path = Path(sys.argv[3])
retrieval_audit_path = Path(sys.argv[4])
blind_quality_review_path = Path(sys.argv[5])
rag_value_review_path = Path(sys.argv[6])
allowed_quality = sys.argv[7]
source_coverage_policy_path = Path(sys.argv[8])
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
candidate_manifest = json.loads(candidate_manifest_path.read_text(encoding="utf-8"))
sealed_retrieval_audit = json.loads(retrieval_audit_path.read_text(encoding="utf-8"))
sealed_blind_quality_review = json.loads(blind_quality_review_path.read_text(encoding="utf-8"))
sealed_rag_value_review = json.loads(rag_value_review_path.read_text(encoding="utf-8"))
source_coverage_policy = json.loads(source_coverage_policy_path.read_text(encoding="utf-8"))
records = [json.loads(line) for line in artifact.read_text(encoding="utf-8").splitlines() if line.strip()]
expected_modes = {"REACTION", "ALIGNMENT", "PLAY", "FOLLOW_UP", "SPECULATION", "CARE", "COORDINATION"}
response_move_provenances = {"FRESH_VERIFIED", "HEURISTIC_OBSERVED", "FRESH_REJECTED", "NONE"}
scene_traits_by_mode = {
    "REACTION": {"REACTION_GOOD_NEWS", "REACTION_SURPRISE_OR_FUNNY"},
    "ALIGNMENT": {"ALIGNMENT_COMPLAINT_OR_LOW_ENERGY"},
    "PLAY": {"PLAY_BANTER"},
    "FOLLOW_UP": {"FOLLOW_UP_STATUS_OR_PROGRESS", "FOLLOW_UP_CHANGE", "FOLLOW_UP_CAUSE"},
    "SPECULATION": {"SPECULATION_CAUSE", "SPECULATION_FUTURE", "SPECULATION_PRESENT"},
    "CARE": {"CARE_PHYSICAL_CONDITION", "CARE_FATIGUE_OVERLOAD", "CARE_EMOTIONAL_DISTRESS"},
    "COORDINATION": {"COORDINATION_CHOICE", "COORDINATION_TIME", "COORDINATION_ACTION_PROPOSAL", "COORDINATION_ROLE_OR_ORDER"},
}
response_moves_by_mode = {
    "REACTION": {"REACTION_GOOD_NEWS", "REACTION_SURPRISE", "REACTION_FUNNY"},
    "ALIGNMENT": {"ALIGNMENT_COMPLAINT", "ALIGNMENT_LOW_ENERGY"},
    "PLAY": {"PLAY_COMPETITIVE_TEASE", "PLAY_FRIENDLY_TEASE", "PLAY_LIGHT_EXAGGERATION"},
    "FOLLOW_UP": {"FOLLOW_UP_STATUS", "FOLLOW_UP_PROGRESS", "FOLLOW_UP_CHANGE", "FOLLOW_UP_CAUSE"},
    "SPECULATION": {"SPECULATION_CAUSE", "SPECULATION_FUTURE", "SPECULATION_PRESENT"},
    "CARE": {"CARE_PHYSICAL", "CARE_FATIGUE", "CARE_EMOTIONAL"},
    "COORDINATION": {"COORDINATION_CHOICE", "COORDINATION_TIME", "COORDINATION_ACTION", "COORDINATION_ROLE"},
}
provider_style_cues_by_mode = {
    "REACTION": {"REACTION_IMMEDIATE", "REACTION_LAUGH_ALONG", "REACTION_WARM_ACK"},
    "ALIGNMENT": {"ALIGNMENT_LOW_KEY_ACK", "ALIGNMENT_SHARED_FEELING"},
    "PLAY": {"PLAY_COUNTERTEASE", "PLAY_LIGHT_EXAGGERATION"},
    "FOLLOW_UP": {"FOLLOW_UP_SOFT_CHECK", "FOLLOW_UP_DIRECT_CHECK"},
    "SPECULATION": {"SPECULATION_LIGHT_HEDGE"},
    "CARE": {"CARE_GENTLE_VALIDATE", "CARE_SOFT_NUDGE"},
    "COORDINATION": {"COORDINATION_CONFIRM", "COORDINATION_PROPOSE", "COORDINATION_ASK_ONE"},
}
expected_card_fields = {
    "schema", "example_id", "response_mode", "situation", "style_signals", "context_bubbles", "response_bubbles",
    "quality", "source_fingerprint", "consent_revision", "combined_chars", "prompt_eligible", "prompt_surface",
    "response_surface_has_card_local_alias", "response_move", "scene_traits", "provider_style_cues", "response_move_provenance", "response_form",
    "response_rhythm", "embedding_model",
}

if set(source_coverage_policy) != {"schema", "source_count", "source_fingerprint_set_sha256"}:
    raise SystemExit("trusted source coverage policy fields are invalid")
if source_coverage_policy.get("schema") != "nia-human-speech-style-source-coverage.v1":
    raise SystemExit("trusted source coverage policy schema is invalid")
if not isinstance(source_coverage_policy.get("source_count"), int) or source_coverage_policy["source_count"] < 1:
    raise SystemExit("trusted source coverage policy count is invalid")
if not isinstance(source_coverage_policy.get("source_fingerprint_set_sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", source_coverage_policy["source_fingerprint_set_sha256"]):
    raise SystemExit("trusted source coverage policy fingerprint digest is invalid")
if manifest.get("schema") != "nia-human-speech-style-import-manifest.v10":
    raise SystemExit("unsupported private import manifest")
if allowed_quality not in {"CURATION_APPROVED", "USER_RELEASED_REVIEW"}:
    raise SystemExit("private import allowed quality is unsupported")
if manifest.get("quality") != allowed_quality:
    raise SystemExit("private import manifest quality does not match the explicit import quality")
artifact_digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
if candidate_manifest.get("schema") != "nia-human-speech-style-runtime-candidate-manifest.v8":
    raise SystemExit("unsupported private candidate manifest")
if candidate_manifest.get("jsonl_sha256") != artifact_digest:
    raise SystemExit("private candidate manifest JSONL digest mismatch")
if manifest.get("input_candidate_manifest_sha256") != hashlib.sha256(candidate_manifest_path.read_bytes()).hexdigest():
    raise SystemExit("private import candidate manifest digest mismatch")
if manifest.get("retrieval_audit_sha256") != hashlib.sha256(retrieval_audit_path.read_bytes()).hexdigest():
    raise SystemExit("private import retrieval audit file digest mismatch")
if manifest.get("retrieval_audit") != sealed_retrieval_audit:
    raise SystemExit("private import retrieval audit file does not match manifest")
if manifest.get("blind_quality_review") != sealed_blind_quality_review:
    raise SystemExit("private import blind quality review file does not match manifest")
if manifest.get("rag_value_review_sha256") != hashlib.sha256(rag_value_review_path.read_bytes()).hexdigest():
    raise SystemExit("private import RAG value review file digest mismatch")
if manifest.get("rag_value_review") != sealed_rag_value_review:
    raise SystemExit("private import RAG value review file does not match manifest")
if manifest.get("record_count") != len(records) or len(records) == 0:
    raise SystemExit("private import record count mismatch")
source_fingerprints = {record.get("source_fingerprint") for record in records}
if None in source_fingerprints or not all(isinstance(fingerprint, str) and re.fullmatch(r"sha256:[0-9a-f]{64}", fingerprint) for fingerprint in source_fingerprints):
    raise SystemExit("private import source fingerprint is invalid")
source_fingerprint_set_sha256 = hashlib.sha256("\n".join(sorted(source_fingerprints)).encode()).hexdigest()
if len(source_fingerprints) != source_coverage_policy["source_count"]:
    raise SystemExit("private import source coverage count is incomplete")
if source_fingerprint_set_sha256 != source_coverage_policy["source_fingerprint_set_sha256"]:
    raise SystemExit("private import source coverage set is incomplete")
for label, sealed_manifest in (("import", manifest), ("candidate", candidate_manifest)):
    if sealed_manifest.get("expected_source_count") != source_coverage_policy["source_count"]:
        raise SystemExit(f"private {label} source coverage count is invalid")
    if sealed_manifest.get("expected_source_fingerprint_set_sha256") != source_coverage_policy["source_fingerprint_set_sha256"]:
        raise SystemExit(f"private {label} source coverage set is invalid")
    if sealed_manifest.get("source_coverage_complete") is not True:
        raise SystemExit(f"private {label} source coverage is incomplete")
if manifest.get("source_count") != len(source_fingerprints):
    raise SystemExit("private import source fingerprint count mismatch")
if manifest.get("source_fingerprint_count") != manifest.get("source_count"):
    raise SystemExit("private import source manifest mismatch")
if candidate_manifest.get("source_fingerprint_count") != len(source_fingerprints):
    raise SystemExit("private candidate source fingerprint count mismatch")
formal_card_count = manifest.get("accepted_card_count", manifest.get("approved_card_count"))
if formal_card_count != len(records):
    raise SystemExit("private import approval count mismatch")
if allowed_quality == "CURATION_APPROVED" and manifest.get("all_cards_formally_approved") is not True:
    raise SystemExit("private import formal approval gate is missing")
if allowed_quality == "USER_RELEASED_REVIEW" and manifest.get("all_cards_user_released") is not True:
    raise SystemExit("private import user release gate is missing")
if artifact_digest != manifest.get("jsonl_sha256"):
    raise SystemExit("private import JSONL digest mismatch")
blind_quality_review = manifest.get("blind_quality_review")
expected_quality_review_fields = {
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
if not isinstance(blind_quality_review, dict) or set(blind_quality_review) != expected_quality_review_fields:
    raise SystemExit("private import blind quality review is invalid")
if blind_quality_review.get("schema") != "nia-human-speech-style-blind-quality-review.v2":
    raise SystemExit("private import blind quality review schema is invalid")
if blind_quality_review.get("artifact_jsonl_sha256") != manifest.get("jsonl_sha256"):
    raise SystemExit("private import blind quality review is not bound to this artifact")
if blind_quality_review.get("verdict") != "PASS":
    raise SystemExit("private import blind quality review has not passed")
retrieval_audit = manifest.get("retrieval_audit")
retrieval_audit_sha256 = manifest.get("retrieval_audit_sha256")
expected_retrieval_audit_fields = {
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
if not isinstance(retrieval_audit, dict) or set(retrieval_audit) != expected_retrieval_audit_fields:
    raise SystemExit("private import retrieval audit is invalid")
if retrieval_audit.get("schema") != "nia-human-speech-style-retrieval-audit.v4":
    raise SystemExit("private import retrieval audit schema is invalid")
if retrieval_audit.get("candidate_jsonl_sha256") != manifest.get("jsonl_sha256"):
    raise SystemExit("private import retrieval audit is not bound to this artifact")
if retrieval_audit.get("retrieval_policy") != "closed_metadata_response_mode_then_semantic_tie_primary_style_cue_contrast_rerank_v11":
    raise SystemExit("private import retrieval audit policy is invalid")
if retrieval_audit.get("embedding_provider") != "openai" or retrieval_audit.get("embedding_model") != "text-embedding-3-small":
    raise SystemExit("private import retrieval audit embedding evidence is invalid")
if retrieval_audit.get("execution_scope") != "ephemeral_h2_only_no_judge_no_discord_no_provider_generation":
    raise SystemExit("private import retrieval audit execution scope is invalid")
if retrieval_audit.get("verdict") != "PASS":
    raise SystemExit("private import retrieval audit has not passed")
if retrieval_audit.get("reason_codes") != []:
    raise SystemExit("private import retrieval audit has unresolved findings")
if not isinstance(retrieval_audit_sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", retrieval_audit_sha256):
    raise SystemExit("private import retrieval audit digest is invalid")
if blind_quality_review.get("retrieval_audit_sha256") != retrieval_audit_sha256:
    raise SystemExit("private import blind quality review is not bound to the retrieval audit")
rag_value_review = manifest.get("rag_value_review")
expected_rag_value_review_fields = {
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
if not isinstance(rag_value_review, dict) or set(rag_value_review) != expected_rag_value_review_fields:
    raise SystemExit("private import RAG value review is invalid")
if rag_value_review.get("schema") != "nia-human-speech-style-rag-value-review.v1":
    raise SystemExit("private import RAG value review schema is invalid")
if rag_value_review.get("review_protocol") != "fixed_mode_baseline_v1":
    raise SystemExit("private import RAG value review protocol is invalid")
if rag_value_review.get("reviewer_type") != "FRESH_COMPARATIVE_VERIFIER":
    raise SystemExit("private import RAG value reviewer is invalid")
if rag_value_review.get("artifact_jsonl_sha256") != manifest.get("jsonl_sha256"):
    raise SystemExit("private import RAG value review is not bound to this artifact")
if rag_value_review.get("retrieval_audit_sha256") != retrieval_audit_sha256:
    raise SystemExit("private import RAG value review is not bound to the retrieval audit")
if rag_value_review.get("verdict") != "PASS":
    raise SystemExit("private import RAG value review has not passed")
value_case_counts = rag_value_review.get("case_count_by_response_mode")
value_top1_counts = rag_value_review.get("top1_value_add_by_response_mode")
value_top2_counts = rag_value_review.get("top2_any_value_add_by_response_mode")
for label, value in (
    ("case count", value_case_counts),
    ("top-1 added value", value_top1_counts),
    ("top-2 added value", value_top2_counts),
):
    if not isinstance(value, dict) or set(value) != expected_modes:
        raise SystemExit(f"private import RAG value review {label} mode map is invalid")
    if not all(isinstance(count, int) and not isinstance(count, bool) and count >= 0 for count in value.values()):
        raise SystemExit(f"private import RAG value review {label} counts are invalid")
value_case_count = rag_value_review.get("case_count")
value_top1_count = rag_value_review.get("top1_value_add_count")
value_top2_count = rag_value_review.get("top2_any_value_add_count")
if not all(isinstance(value, int) and not isinstance(value, bool) and value >= 0 for value in (value_case_count, value_top1_count, value_top2_count)):
    raise SystemExit("private import RAG value review totals are invalid")
if value_case_count != sum(value_case_counts.values()) or value_case_count < 70 or any(count < 10 for count in value_case_counts.values()):
    raise SystemExit("private import RAG value review coverage is insufficient")
if value_top1_count != sum(value_top1_counts.values()) or value_top2_count != sum(value_top2_counts.values()):
    raise SystemExit("private import RAG value review totals are inconsistent")
if value_top1_count > value_top2_count or value_top2_count > value_case_count:
    raise SystemExit("private import RAG value review totals are invalid")
if value_top1_count * 100 < value_case_count * 60 or value_top2_count * 100 < value_case_count * 75:
    raise SystemExit("private import RAG value review does not meet overall thresholds")
for mode in expected_modes:
    if value_top1_counts[mode] > value_top2_counts[mode] or value_top2_counts[mode] > value_case_counts[mode]:
        raise SystemExit("private import RAG value review response mode counts are invalid")
    if value_top1_counts[mode] * 100 < value_case_counts[mode] * 50 or value_top2_counts[mode] * 100 < value_case_counts[mode] * 70:
        raise SystemExit("private import RAG value review does not meet response mode thresholds")
for field in ("worse_than_mode_baseline_count", "unsupported_specificity_count", "unsafe_provider_surface_count"):
    value = rag_value_review.get(field)
    if not isinstance(value, int) or isinstance(value, bool) or value != 0:
        raise SystemExit("private import RAG value review has unresolved findings")
fixed_probe_fields = {
    "case_count",
    "exact_mode_return_case_count",
    "policy_abstention_count",
    "returned_reference_card_count",
    "returned_reference_case_count",
    "unexpected_post_query_empty_count",
}
independent_holdout_fields = fixed_probe_fields | {"source_diverse_top2_case_count"}
for label, expected_fields, minimum_case_count in (
    ("fixed probe", fixed_probe_fields, 35),
    ("independent holdout", independent_holdout_fields, 35),
):
    suite = retrieval_audit.get(label.replace(" ", "_"))
    if not isinstance(suite, dict) or set(suite) != expected_fields:
        raise SystemExit(f"private import retrieval audit {label} is invalid")
    if not all(isinstance(value, int) and not isinstance(value, bool) and value >= 0 for value in suite.values()):
        raise SystemExit(f"private import retrieval audit {label} counts are invalid")
    if suite["case_count"] < minimum_case_count:
        raise SystemExit(f"private import retrieval audit {label} coverage is insufficient")
    if suite["returned_reference_case_count"] > suite["case_count"]:
        raise SystemExit(f"private import retrieval audit {label} reference count is invalid")
    if suite["exact_mode_return_case_count"] != suite["returned_reference_case_count"]:
        raise SystemExit(f"private import retrieval audit {label} response mode preservation is invalid")
    if suite["policy_abstention_count"] > suite["case_count"]:
        raise SystemExit(f"private import retrieval audit {label} abstention count is invalid")
    if suite["unexpected_post_query_empty_count"] != 0:
        raise SystemExit(f"private import retrieval audit {label} has post-query empty results")
if retrieval_audit["independent_holdout"]["returned_reference_case_count"] * 100 < retrieval_audit["independent_holdout"]["case_count"] * 80:
    raise SystemExit("private import retrieval audit independent holdout coverage is insufficient")
if retrieval_audit["independent_holdout"]["source_diverse_top2_case_count"] != retrieval_audit["independent_holdout"]["returned_reference_case_count"]:
    raise SystemExit("private import retrieval audit independent holdout source diversity is insufficient")
if len({record.get("example_id") for record in records}) != len(records):
    raise SystemExit("private import example ids are duplicated")
record_modes = {record.get("response_mode") for record in records}
if not record_modes or not record_modes <= expected_modes:
    raise SystemExit("private import response mode coverage is invalid")
prompt_eligible = [record for record in records if record.get("prompt_eligible") is True]
if not prompt_eligible:
    raise SystemExit("private import has no prompt-eligible cards")
if manifest.get("prompt_eligible_count") != len(prompt_eligible):
    raise SystemExit("private import prompt eligible count mismatch")
if manifest.get("prompt_disabled_count") != len(records) - len(prompt_eligible):
    raise SystemExit("private import prompt disabled count mismatch")
prompt_eligible_modes = {record.get("response_mode") for record in prompt_eligible}
if prompt_eligible_modes != expected_modes:
    raise SystemExit("private import prompt eligible response mode coverage is incomplete")
if manifest.get("prompt_eligible_by_response_mode") != {
    mode: sum(record.get("response_mode") == mode for record in prompt_eligible)
    for mode in sorted(prompt_eligible_modes)
}:
    raise SystemExit("private import prompt eligible mode count mismatch")
if manifest.get("retrieval_policy") != "closed_metadata_response_mode_then_semantic_tie_primary_style_cue_contrast_rerank_v11":
    raise SystemExit("private import retrieval policy is invalid")
if candidate_manifest.get("retrieval_policy") != "closed_metadata_response_mode_then_semantic_tie_primary_style_cue_contrast_rerank_v11":
    raise SystemExit("private candidate retrieval policy is invalid")
if manifest.get("response_move_policy") != "observed_response_metadata_with_fresh_review_overlay_v1":
    raise SystemExit("private import does not use fresh-verifier response moves")
response_move_review_digest = manifest.get("response_move_review_ledger_sha256")
if not isinstance(response_move_review_digest, str) or not re.fullmatch(r"[0-9a-f]{64}", response_move_review_digest):
    raise SystemExit("private import response-move review ledger is invalid")
observed_response_moves = sum(record.get("response_move") is not None for record in records)
fresh_verified_response_moves = manifest.get("fresh_verified_response_move_count")
heuristically_observed_response_moves = manifest.get("heuristically_observed_response_move_count")
rejected_response_moves = manifest.get("rejected_response_move_review_count")
if not isinstance(fresh_verified_response_moves, int) or isinstance(fresh_verified_response_moves, bool) or fresh_verified_response_moves <= 0:
    raise SystemExit("private import fresh verified response-move count is invalid")
if not isinstance(heuristically_observed_response_moves, int) or isinstance(heuristically_observed_response_moves, bool) or heuristically_observed_response_moves < 0:
    raise SystemExit("private import observed response-move count is invalid")
if not isinstance(rejected_response_moves, int) or isinstance(rejected_response_moves, bool) or rejected_response_moves < 0:
    raise SystemExit("private import rejected response-move review count is invalid")
response_move_provenance_counts = {}
scene_trait_counts = {}
provider_style_cue_counts = {}
for record in records:
    provenance = record.get("response_move_provenance")
    response_move_provenance_counts[provenance] = response_move_provenance_counts.get(provenance, 0) + 1
    for trait in record.get("scene_traits", []):
        scene_trait_counts[trait] = scene_trait_counts.get(trait, 0) + 1
    for cue in record.get("provider_style_cues", []):
        provider_style_cue_counts[cue] = provider_style_cue_counts.get(cue, 0) + 1
if fresh_verified_response_moves + heuristically_observed_response_moves != observed_response_moves:
    raise SystemExit("private import response-move provenance count mismatch")
if rejected_response_moves + response_move_provenance_counts.get("NONE", 0) != len(records) - observed_response_moves:
    raise SystemExit("private import response-move provenance count mismatch")
if manifest.get("response_move_provenance_counts") != dict(sorted(response_move_provenance_counts.items())):
    raise SystemExit("private import response-move provenance count map mismatch")
if manifest.get("scene_trait_counts") != dict(sorted(scene_trait_counts.items())):
    raise SystemExit("private import scene-trait count map mismatch")
if manifest.get("provider_style_cue_counts") != dict(sorted(provider_style_cue_counts.items())):
    raise SystemExit("private import provider-style-cue count map mismatch")
if candidate_manifest.get("provider_style_cue_counts") != dict(sorted(provider_style_cue_counts.items())):
    raise SystemExit("private candidate provider-style-cue count map mismatch")
if (
    fresh_verified_response_moves != response_move_provenance_counts.get("FRESH_VERIFIED", 0)
    or heuristically_observed_response_moves != response_move_provenance_counts.get("HEURISTIC_OBSERVED", 0)
    or rejected_response_moves != response_move_provenance_counts.get("FRESH_REJECTED", 0)
):
    raise SystemExit("private import response-move provenance aggregates mismatch")
if manifest.get("prompt_surface_policy") != "closed_style_pattern_v1":
    raise SystemExit("private import prompt surface policy is invalid")
prompt_surfaces = {"STYLE_PATTERN", "AUDIT_ONLY"}
if manifest.get("prompt_surface_counts") != {
    surface: sum(record.get("prompt_surface") == surface for record in records)
    for surface in sorted(prompt_surfaces)
    if any(record.get("prompt_surface") == surface for record in records)
}:
    raise SystemExit("private import prompt surface count mismatch")
forbidden_provenance_fields = {
    "source_id",
    "source_trace",
    "month",
    "preview_sequence",
    "message_id",
    "original_path",
    "source_path",
    "ordinal",
}
delivery_only_rhythm_cues = {
    "TINY_REPLY", "SHORT_REPLY", "MEDIUM_REPLY", "LONGER_REPLY", "TRAILING_PAUSE",
    "CASUAL_SHORT_FORM", "SOFT_EMOTION_MARKER", "SINGLE_BUBBLE", "MULTI_BUBBLE",
}
for record in records:
    if set(record) != expected_card_fields:
        raise SystemExit("private import card fields are invalid")
    if record.get("schema") != "nia-human-speech-style-import-card.v4":
        raise SystemExit("private import card schema is invalid")
    if record.get("quality") != allowed_quality:
        raise SystemExit("private import card quality does not match the explicit import quality")
    if record.get("consent_revision") != manifest.get("consent_revision"):
        raise SystemExit("private import consent revision is inconsistent")
    if forbidden_provenance_fields & set(record):
        raise SystemExit("private import retains source provenance")
    if not record.get("context_bubbles") or not record.get("response_bubbles"):
        raise SystemExit("private import card bubbles are incomplete")
    if not re.fullmatch(r"human-style-[0-9]{6}", str(record.get("example_id"))):
        raise SystemExit("private import example id is invalid")
    if not isinstance(record.get("prompt_eligible"), bool):
        raise SystemExit("private import prompt eligibility is invalid")
    prompt_surface = record.get("prompt_surface")
    if prompt_surface not in prompt_surfaces:
        raise SystemExit("private import prompt surface is invalid")
    if record["prompt_eligible"] != (prompt_surface == "STYLE_PATTERN"):
        raise SystemExit("private import prompt surface and eligibility disagree")
    if not isinstance(record.get("response_surface_has_card_local_alias"), bool):
        raise SystemExit("private import alias safety flag is invalid")
    for metadata_key in ("response_move", "response_form"):
        metadata = record.get(metadata_key)
        if metadata is not None and (not isinstance(metadata, str) or not metadata.strip()):
            raise SystemExit("private import response metadata is invalid")
    response_mode = record.get("response_mode")
    response_move = record.get("response_move")
    if response_move is not None and response_move not in response_moves_by_mode.get(response_mode, set()):
        raise SystemExit("private import response move does not match response mode")
    provenance = record.get("response_move_provenance")
    if provenance not in response_move_provenances:
        raise SystemExit("private import response-move provenance is invalid")
    if (provenance in {"FRESH_VERIFIED", "HEURISTIC_OBSERVED"}) != (response_move is not None):
        raise SystemExit("private import response-move provenance does not match response move")
    scene_traits = record.get("scene_traits")
    if not isinstance(scene_traits, list) or len(scene_traits) > 2 or len(set(scene_traits)) != len(scene_traits):
        raise SystemExit("private import scene traits are invalid")
    if any(not isinstance(trait, str) or trait not in scene_traits_by_mode.get(response_mode, set()) for trait in scene_traits):
        raise SystemExit("private import scene traits do not match response mode")
    provider_style_cues = record.get("provider_style_cues")
    if not isinstance(provider_style_cues, list) or len(provider_style_cues) > 1 or len(set(provider_style_cues)) != len(provider_style_cues):
        raise SystemExit("private import provider style cues are invalid")
    if any(not isinstance(cue, str) or cue not in provider_style_cues_by_mode.get(response_mode, set()) for cue in provider_style_cues):
        raise SystemExit("private import provider style cues do not match response mode")
    if record["prompt_eligible"] and len(provider_style_cues) != 1:
        raise SystemExit("private import style pattern must have exactly one observed provider style cue")
    rhythm = record.get("response_rhythm")
    if not isinstance(rhythm, list) or not all(isinstance(cue, str) and cue.strip() for cue in rhythm):
        raise SystemExit("private import response rhythm metadata is invalid")
    if record["prompt_eligible"] and record.get("response_form") is None:
        raise SystemExit("private import style pattern has no response form")
    if record["prompt_eligible"] and not any(cue not in delivery_only_rhythm_cues for cue in rhythm):
        raise SystemExit("private import style pattern has no observed response rhythm")

print(
    len(records),
    manifest["source_count"],
    len(record_modes),
    len(prompt_eligible),
    len(prompt_eligible_modes),
    source_coverage_policy["source_fingerprint_set_sha256"],
)
PY
)"
read -r EXPECTED_COUNT EXPECTED_SOURCE_COUNT EXPECTED_MODE_COUNT EXPECTED_ENABLED_COUNT EXPECTED_ENABLED_MODE_COUNT EXPECTED_SOURCE_SET_SHA256 <<<"$artifact_metadata"

[[ "$EXPECTED_COUNT" =~ ^[1-9][0-9]*$ ]] || fail "private import expected count is invalid"
[[ "$EXPECTED_SOURCE_COUNT" =~ ^[1-9][0-9]*$ ]] || fail "private import expected source count is invalid"
[[ "$EXPECTED_MODE_COUNT" =~ ^[1-7]$ ]] || fail "private import expected mode count is invalid"
[[ "$EXPECTED_ENABLED_COUNT" =~ ^[1-9][0-9]*$ ]] || fail "private import expected searchable count is invalid"
[[ "$EXPECTED_ENABLED_MODE_COUNT" =~ ^[1-7]$ ]] || fail "private import expected searchable mode count is invalid"
[[ "$EXPECTED_SOURCE_SET_SHA256" =~ ^[0-9a-f]{64}$ ]] || fail "private import expected source set is invalid"
echo "▶ private artifact verified: cards=$EXPECTED_COUNT searchable=$EXPECTED_ENABLED_COUNT sources=$EXPECTED_SOURCE_COUNT modes=$EXPECTED_MODE_COUNT"

compose=(docker compose --env-file /dev/null -f "$COMPOSE_FILE")
"${compose[@]}" ps --status running "$DB_SERVICE" | grep -q "$DB_SERVICE" || fail "database service is not running"
"${compose[@]}" exec -T "$DB_SERVICE" psql -U central -d central -Atc \
  "SELECT EXISTS (SELECT 1 FROM flyway_schema_history WHERE script = 'V91__nia_human_speech_style_rag.sql' AND success);" \
  | grep -qx 't' || fail "V91 migration is not applied; deploy the supporting image first"

container_name="central-server-style-rag-import-${GITHUB_RUN_ID:-manual}"
echo "▶ one-shot Speech-style RAG import starts (Discord and autonomous send disabled)"
"${compose[@]}" run --rm --no-deps --name "$container_name" \
  --user "$IMPORT_RUN_USER" \
  -v "$IMPORT_DIRECTORY:/private/human-speech-style-rag-import:ro" \
  -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  -e CENTRAL_DISCORD_ENABLED=false \
  -e NEXA_AUTONOMOUS_SEND_ENABLED=false \
  -e CENTRAL_NEXA_PARTICIPATION_GLOBAL_DEFAULT_LANE=OFF \
  -e NIA_WEB_DEMO_ENABLED=false \
  -e NEXA_SPEECH_STYLE_RAG_ENABLED=false \
  -e NEXA_SPEECH_STYLE_RAG_IMPORT_ON_STARTUP=true \
  -e NEXA_SPEECH_STYLE_RAG_IMPORT_EXIT_AFTER_COMPLETION=true \
  -e NEXA_SPEECH_STYLE_RAG_IMPORT_ARTIFACT_DIR=/private/human-speech-style-rag-import \
  -e NEXA_SPEECH_STYLE_RAG_IMPORT_ALLOWED_QUALITY="$IMPORT_ALLOWED_QUALITY" \
  "$APP_SERVICE"

read -r IMPORTED_COUNT ENABLED_COUNT ENCRYPTED_PAYLOADS ENCRYPTED_VECTORS SOURCE_COUNT MODE_COUNT ENABLED_MODE_COUNT <<EOF
$("${compose[@]}" exec -T "$DB_SERVICE" psql -U central -d central -At -F ' ' -c \
  "SELECT count(*), count(*) FILTER (WHERE enabled), count(*) FILTER (WHERE payload_json LIKE 'enc1:%'), count(*) FILTER (WHERE embedding_json LIKE 'enc1:%'), count(DISTINCT source_fingerprint), count(DISTINCT response_mode), count(DISTINCT response_mode) FILTER (WHERE enabled) FROM nia_human_speech_style_example;")
EOF

[ "$IMPORTED_COUNT" = "$EXPECTED_COUNT" ] || fail "imported card count mismatch"
[ "$ENABLED_COUNT" = "$EXPECTED_ENABLED_COUNT" ] || fail "imported searchable card count mismatch"
[ "$ENCRYPTED_PAYLOADS" = "$EXPECTED_COUNT" ] || fail "plaintext payload rows detected"
[ "$ENCRYPTED_VECTORS" = "$EXPECTED_COUNT" ] || fail "plaintext embedding rows detected"
[ "$SOURCE_COUNT" = "$EXPECTED_SOURCE_COUNT" ] || fail "imported source count mismatch"
[ "$MODE_COUNT" = "$EXPECTED_MODE_COUNT" ] || fail "imported response mode coverage mismatch"
[ "$ENABLED_MODE_COUNT" = "$EXPECTED_ENABLED_MODE_COUNT" ] || fail "imported searchable response mode coverage mismatch"

IMPORTED_SOURCE_SET_SHA256="$("${compose[@]}" exec -T "$DB_SERVICE" psql -U central -d central -Atc \
  "SELECT DISTINCT source_fingerprint FROM nia_human_speech_style_example ORDER BY source_fingerprint;" \
  | python3 -c 'import hashlib, sys; fingerprints = [line.strip() for line in sys.stdin if line.strip()]; print(hashlib.sha256("\n".join(fingerprints).encode()).hexdigest())')"
[ "$IMPORTED_SOURCE_SET_SHA256" = "$EXPECTED_SOURCE_SET_SHA256" ] || fail "imported source coverage set mismatch"

echo "✅ Speech-style RAG import complete: cards=$IMPORTED_COUNT searchable=$ENABLED_COUNT sources=$SOURCE_COUNT encrypted_payloads=$ENCRYPTED_PAYLOADS encrypted_vectors=$ENCRYPTED_VECTORS"
